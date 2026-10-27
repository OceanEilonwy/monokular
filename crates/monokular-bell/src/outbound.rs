//! Dings go to addresses the tills give the Bell: a push endpoint could
//! name an address inside the network the Bell runs in. So, as monokulo
//! sends its webhooks and pushes: a URL whose host is, or resolves to, a
//! loopback, private, link-local or otherwise internal address is refused
//! at every request (DNS can change between requests), redirects aren't
//! followed and no proxy is used, unless `bell.allow_private_endpoints`
//! is on ([`Reach::AnyAddress`]). An onion address never gets this far: a
//! [`push::Endpoint`] can't be one.

use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr};
use std::sync::Arc;
use std::time::Duration;

/// Which addresses a ding may go to, as `bell.allow_private_endpoints`
/// says.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Reach {
    /// Public addresses only.
    Public,
    /// Private, loopback and link-local ones too.
    AnyAddress,
}

impl Reach {
    pub fn allowing_private(allow_private_endpoints: bool) -> Self {
        if allow_private_endpoints {
            Reach::AnyAddress
        } else {
            Reach::Public
        }
    }
}

/// The client dings are sent with: redirects off (one to a private address
/// must not be followed), `timeout` for each, and the checking resolver.
/// The resolver is what makes the check hold: the addresses a name
/// resolves to are checked by the very lookup the connection is made
/// from, so a name can't answer the check with one address and the
/// connection with another. Fails only if TLS can't be set up.
pub fn client(reach: Reach, timeout: Duration) -> reqwest::Result<reqwest::Client> {
    let builder = reqwest::Client::builder()
        .redirect(reqwest::redirect::Policy::none())
        .user_agent(crate::USER_AGENT)
        .timeout(timeout)
        .dns_resolver(Arc::new(CheckingResolver { reach }));
    // A proxy connects on the Bell's behalf, past the checking resolver.
    match reach {
        Reach::Public => builder.no_proxy(),
        Reach::AnyAddress => builder,
    }
    .build()
}

/// The system resolver, with every answer checked before it is connected
/// to. An IP literal in a URL never reaches a resolver: [`check_host`]
/// checks those.
struct CheckingResolver {
    reach: Reach,
}

impl reqwest::dns::Resolve for CheckingResolver {
    fn resolve(&self, name: reqwest::dns::Name) -> reqwest::dns::Resolving {
        let reach = self.reach;
        Box::pin(async move {
            // Port 0: the connector sets the URL's port on each address.
            let addrs: Vec<SocketAddr> =
                tokio::net::lookup_host((name.as_str(), 0)).await?.collect();
            if addrs.is_empty() {
                return Err(BoxError::from(OutboundError::Unresolvable));
            }
            // Every answer has to pass: a name resolving to both a public
            // and a private address is the rebinding this defends against.
            if reach == Reach::Public && addrs.iter().any(|addr| is_private(addr.ip())) {
                return Err(BoxError::from(OutboundError::PrivateAddress));
            }
            Ok::<reqwest::dns::Addrs, BoxError>(Box::new(addrs.into_iter()))
        })
    }
}

type BoxError = Box<dyn std::error::Error + Send + Sync>;

/// Why a ding wasn't sent to an address.
#[derive(Debug, Clone, Copy, PartialEq, Eq, thiserror::Error)]
pub enum OutboundError {
    #[error("the endpoint's host doesn't resolve")]
    Unresolvable,
    #[error("the endpoint is, or resolves to, a private or loopback address")]
    PrivateAddress,
}

/// Refuses a URL whose host is an IP literal of a private, loopback or
/// link-local address, or a `localhost` name (unless `reach` allows any):
/// the half of the check a resolver never sees, and the name that is
/// loopback without asking one. Checked at registration as well as at
/// each ding, so a till isn't registered for an endpoint the Bell will
/// never send to.
pub fn check_host(url: &url::Url, reach: Reach) -> Result<(), OutboundError> {
    if reach == Reach::AnyAddress {
        return Ok(());
    }
    let ip = match url.host() {
        Some(url::Host::Ipv4(ip)) => IpAddr::V4(ip),
        Some(url::Host::Ipv6(ip)) => IpAddr::V6(ip),
        Some(url::Host::Domain(name)) if is_localhost(name) => {
            return Err(OutboundError::PrivateAddress);
        }
        Some(url::Host::Domain(_)) => return Ok(()),
        None => return Err(OutboundError::Unresolvable),
    };
    if is_private(ip) {
        return Err(OutboundError::PrivateAddress);
    }
    Ok(())
}

/// `localhost` and every name under it, with or without the root's dot:
/// loopback whatever a resolver says (RFC 6761 §6.3), so refused without
/// asking one. `Url` has lowercased the name already.
fn is_localhost(name: &str) -> bool {
    let name = name.strip_suffix('.').unwrap_or(name);
    name == "localhost" || name.ends_with(".localhost")
}

/// Whether `ip` is loopback, private, link-local or otherwise no place to
/// send a ding: monokulo's rule for its webhooks and pushes
/// (`credentials::webhook_sign::is_disallowed_address`), which the Bell
/// can't take without that crate's password hashing.
fn is_private(ip: IpAddr) -> bool {
    // An IPv4 address inside an IPv6 one is judged as the IPv4 address,
    // which is where a connection to it lands.
    let ip = match ip {
        IpAddr::V6(v6) => embedded_ipv4(v6).map_or(IpAddr::V6(v6), IpAddr::V4),
        v4 => v4,
    };
    match ip {
        IpAddr::V4(v4) => {
            let o = v4.octets();
            v4.is_loopback()
                || v4.is_private()
                || v4.is_link_local()
                || v4.is_broadcast()
                // "This network" (0.0.0.0/8): 0.0.0.0 reaches this host.
                || o[0] == 0
                // Carrier-grade NAT (100.64.0.0/10).
                || (o[0] == 100 && (o[1] & 0xc0) == 0x40)
                // Benchmarking (198.18.0.0/15).
                || (o[0] == 198 && (o[1] & 0xfe) == 18)
                // Multicast and the reserved 240.0.0.0/4.
                || o[0] >= 224
        }
        IpAddr::V6(v6) => {
            let [first, second, ..] = v6.segments();
            v6.is_loopback()
                || v6.is_unspecified()
                || (first & 0xfe00) == 0xfc00 // unique local (fc00::/7)
                || (first & 0xffc0) == 0xfe80 // link-local (fe80::/10)
                || (first & 0xff00) == 0xff00 // multicast (ff00::/8)
                || (first == 0x0100 && second == 0) // discard-only (100::/64)
        }
    }
}

/// The IPv4 address an IPv6 one carries: IPv4-mapped (`::ffff:0:0/96`)
/// and IPv4-compatible (`::/96`, so `::1` is 0.0.0.1), NAT64
/// (`64:ff9b::/96` and `64:ff9b:1::/48`), 6to4 (`2002::/16`) and Teredo
/// (`2001::/32`, inverted), as monokulo reads them
/// (`telemetry::redact::embedded_ipv4`).
fn embedded_ipv4(v6: Ipv6Addr) -> Option<Ipv4Addr> {
    if let Some(v4) = v6.to_ipv4() {
        return Some(v4);
    }
    let segments = v6.segments();
    let octets = v6.octets();
    let last_four = |invert: bool| {
        let bytes = [octets[12], octets[13], octets[14], octets[15]];
        Ipv4Addr::from(if invert { bytes.map(|b| !b) } else { bytes })
    };
    match segments {
        [0x0064, 0xff9b, 0, 0, 0, 0, _, _] | [0x0064, 0xff9b, 0x0001, ..] => Some(last_four(false)),
        [0x2002, ..] => Some(Ipv4Addr::from([octets[2], octets[3], octets[4], octets[5]])),
        [0x2001, 0x0000, ..] => Some(last_four(true)),
        _ => None,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn ip(text: &str) -> IpAddr {
        text.parse().unwrap()
    }

    #[test]
    fn internal_addresses_are_private_in_either_family() {
        for private in [
            "127.0.0.1",
            "10.1.2.3",
            "172.16.0.1",
            "192.168.1.1",
            "169.254.169.254",
            "0.0.0.0",
            "100.64.0.1",
            "198.18.0.1",
            "224.0.0.1",
            "255.255.255.255",
            "::1",
            "::",
            "fc00::1",
            "fe80::1",
            "ff02::1",
            "::ffff:127.0.0.1",
            "::127.0.0.1",
            "64:ff9b::a00:1",
            "2002:c0a8:101::1",
            // Teredo, carrying 127.0.0.1 inverted.
            "2001:0:4136:e378:8000:63bf:80ff:fffe",
        ] {
            assert!(is_private(ip(private)), "{private}");
        }
        for public in [
            "93.184.216.34",
            "1.1.1.1",
            "2606:4700:4700::1111",
            "::ffff:8.8.8.8",
        ] {
            assert!(!is_private(ip(public)), "{public}");
        }
    }

    /// An IP literal never reaches the resolver, so it is checked before
    /// the request, as is `localhost`; any other name is left to the
    /// resolver.
    #[test]
    fn an_ip_literal_is_checked_before_the_request() {
        let url = |text: &str| url::Url::parse(text).unwrap();
        assert_eq!(
            check_host(&url("http://127.0.0.1:1/push"), Reach::Public),
            Err(OutboundError::PrivateAddress)
        );
        assert_eq!(
            check_host(&url("https://[::ffff:10.0.0.1]/push"), Reach::Public),
            Err(OutboundError::PrivateAddress)
        );
        assert_eq!(
            check_host(&url("http://127.0.0.1:1/push"), Reach::AnyAddress),
            Ok(())
        );
        assert_eq!(
            check_host(&url("https://1.1.1.1/push"), Reach::Public),
            Ok(())
        );
        assert_eq!(
            check_host(&url("https://push.example.org/push"), Reach::Public),
            Ok(())
        );
        // Loopback by name, in any spelling, without asking a resolver.
        for name in [
            "https://localhost/push",
            "http://LocalHost:8080/push",
            "https://localhost./push",
            "https://push.localhost/push",
        ] {
            assert_eq!(
                check_host(&url(name), Reach::Public),
                Err(OutboundError::PrivateAddress),
                "{name}"
            );
            assert_eq!(check_host(&url(name), Reach::AnyAddress), Ok(()));
        }
        assert_eq!(
            check_host(&url("https://localhost.example.org/push"), Reach::Public),
            Ok(())
        );
    }

    /// The resolver refuses a name that resolves to a private address, so
    /// `localhost` is refused there.
    #[tokio::test]
    async fn the_resolver_refuses_a_name_with_a_private_address() {
        use reqwest::dns::Resolve as _;
        let name = || "localhost".parse::<reqwest::dns::Name>().unwrap();
        let public = CheckingResolver {
            reach: Reach::Public,
        };
        let refused = match public.resolve(name()).await {
            Err(error) => error.to_string(),
            Ok(_) => panic!("a loopback answer must be refused"),
        };
        assert!(refused.contains("private or loopback"), "{refused}");
        let any = CheckingResolver {
            reach: Reach::AnyAddress,
        };
        let addrs: Vec<_> = any.resolve(name()).await.unwrap().collect();
        assert!(
            addrs.iter().all(|addr| addr.ip().is_loopback()),
            "{addrs:?}"
        );
    }
}
