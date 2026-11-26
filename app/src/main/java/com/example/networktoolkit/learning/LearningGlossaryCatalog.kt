package com.example.networktoolkit.learning

/**
 * Expandable offline networking glossary covering fundamentals, diagnostics,
 * Wi-Fi/RF concepts, and throughput testing as exposed in Network Technician Toolkit.
 */
object LearningGlossaryCatalog {

    val entries: List<GlossaryEntry> = listOf(
        // =====================================================================
        // 1. NETWORKING FUNDAMENTALS
        // =====================================================================
        GlossaryEntry(
            id = "ip_address",
            term = "IP Address",
            fullName = "Internet Protocol Address",
            category = GlossaryCategory.FUNDAMENTALS,
            plainEnglishDefinition = "A numeric label assigned to a device so other devices on a local network or the internet know where to send data.",
            technicalExplanation = "A Layer-3 network identifier assigned statically, via DHCP, or via SLAAC/DHCPv6, formatted as IPv4 (32-bit dotted decimal) or IPv6 (128-bit hexadecimal).",
            appExample = "Shown on the Home connectivity card, Network Info ('IP Address' / Connection Diagnostics), Ping, Traceroute, and LAN Scanner.",
            whyItMatters = "Confirms whether a device received an address on the right subnet or fell back to an unroutable link-local address.",
            conciseReference = "Layer-3 logical host identifier (IPv4 32-bit / IPv6 128-bit) bound per network interface."
        ),
        GlossaryEntry(
            id = "ipv4",
            term = "IPv4",
            fullName = "Internet Protocol Version 4",
            category = GlossaryCategory.FUNDAMENTALS,
            plainEnglishDefinition = "The classic 4-number address style separated by dots, such as 192.168.1.50.",
            technicalExplanation = "Uses 32-bit addresses grouped into four 0-255 octets, divided into network and host portions by a subnet mask or CIDR prefix.",
            appExample = "Used in Subnet Calculator (IPv4 CIDR), LAN Subnet Scanner, and IPv4 connection diagnostics.",
            whyItMatters = "Most local routers, printers, and enterprise LANs still rely on IPv4 addressing for internal troubleshooting.",
            conciseReference = "32-bit dotted-quad Layer-3 addressing; subject to RFC 1918 private space and NAT exhaustion."
        ),
        GlossaryEntry(
            id = "ipv6",
            term = "IPv6",
            fullName = "Internet Protocol Version 6",
            category = GlossaryCategory.FUNDAMENTALS,
            plainEnglishDefinition = "The newer address format using letters and colons (such as 2001:4860:4860::8888) built to support vastly more devices.",
            technicalExplanation = "128-bit hierarchical addressing with built-in SLAAC autoconfiguration, link-local fe80::/10 scope, and no need for local NAT on public prefixes.",
            appExample = "Displayed alongside IPv4 in Connection Diagnostics, DNS AAAA lookups, Traceroute, and iPerf3 connections.",
            whyItMatters = "Modern mobile carriers run IPv6-native cores; misbehaving IPv6 routes or PMTUD blackholes can break specific services even when IPv4 works.",
            conciseReference = "128-bit colon-hex addressing; supports dual-stack or IPv6-only + 464XLAT cellular transports."
        ),
        GlossaryEntry(
            id = "subnet_mask",
            term = "Subnet Mask",
            fullName = "Subnetwork Mask",
            category = GlossaryCategory.FUNDAMENTALS,
            plainEnglishDefinition = "A number (like 255.255.255.0) that tells your device which part of an IP address identifies the local neighborhood and which part identifies the device itself.",
            technicalExplanation = "A 32-bit contiguous bitmask of 1s (network prefix) followed by 0s (host bits) used to determine whether a destination IP is local or requires the default gateway.",
            appExample = "Computed in Subnet Calculator alongside Wildcard Mask, Network Address, Broadcast Address, and Binary Octets.",
            whyItMatters = "A mismatched subnet mask causes hosts on the same cable or SSID to fail communicating directly or reach the wrong broadcast address.",
            conciseReference = "Contiguous 32-bit network/host partition mask (e.g., 255.255.255.0 = /24)."
        ),
        GlossaryEntry(
            id = "cidr",
            term = "CIDR",
            fullName = "Classless Inter-Domain Routing",
            category = GlossaryCategory.FUNDAMENTALS,
            plainEnglishDefinition = "Shorthand notation like /24 at the end of an IP address that quickly tells you how big the network is.",
            technicalExplanation = "Represents the number of leading network bits (0 to 32 in IPv4, 0 to 128 in IPv6), replacing legacy Class A/B/C boundaries.",
            appExample = "Entered directly in Subnet Calculator (e.g., 192.168.1.0/24) and LAN Scanner local subnet sweeps.",
            whyItMatters = "Lets technicians size VLANs, verify router DHCP pool boundaries, and inspect carrier/VPN route prefixes accurately.",
            conciseReference = "Prefix length /n specifying leading network bits; usable IPv4 hosts = 2^(32-n) - 2 for n <= 30."
        ),
        GlossaryEntry(
            id = "default_gateway",
            term = "Default Gateway",
            fullName = "Default Router / Gateway IP",
            category = GlossaryCategory.FUNDAMENTALS,
            plainEnglishDefinition = "The local router IP address your phone sends traffic to whenever a destination is outside your local network.",
            technicalExplanation = "Next-hop Layer-3 router matched by the default route (0.0.0.0/0 or ::/0) in the device routing table.",
            appExample = "Probed automatically by the Home connectivity monitor, Gateway Info in Network Info, and selectable as a Ping/iPerf3 target chip.",
            whyItMatters = "Separates local Wi-Fi/LAN issues from upstream ISP outages: if the gateway responds cleanly, local wireless/Ethernet transport is intact.",
            conciseReference = "0.0.0.0/0 next-hop router; full-tunnel VPNs may suppress or override direct local gateway reachability."
        ),
        GlossaryEntry(
            id = "dns",
            term = "DNS",
            fullName = "Domain Name System",
            category = GlossaryCategory.FUNDAMENTALS,
            plainEnglishDefinition = "The internet's phonebook that translates human-readable names like google.com into numeric IP addresses.",
            technicalExplanation = "Hierarchical distributed directory queried over UDP/TCP port 53, DNS-over-TLS (DoT port 853), or DNS-over-HTTPS (DoH port 443).",
            appExample = "Inspected in Network Info and queried directly in DNS Lookup & Diagnostics (system resolver + DoH).",
            whyItMatters = "Many 'internet is down' tickets are actually DNS failures where IP connectivity works fine but hostnames cannot resolve.",
            conciseReference = "Name-to-record resolution (UDP/TCP 53, DoT 853, DoH 443); inspect Private DNS state on Android 9+."
        ),
        GlossaryEntry(
            id = "dhcp",
            term = "DHCP",
            fullName = "Dynamic Host Configuration Protocol",
            category = GlossaryCategory.FUNDAMENTALS,
            plainEnglishDefinition = "The automatic system a router uses to hand out IP addresses, subnet masks, gateways, and DNS servers when you join a network.",
            technicalExplanation = "UDP-based lease negotiation (Discover, Offer, Request, Acknowledge) that dynamically provisions IPv4 host configuration and DNS options.",
            appExample = "Observed indirectly via assigned Local IP, Gateway, Subnet, and DNS servers inside Connection Diagnostics.",
            whyItMatters = "Exhausted DHCP scopes or rogue DHCP servers cause intermittent drops, 169.254.x.x self-assigned IPs, or wrong gateways.",
            conciseReference = "UDP 67/68 DORA lease provisioning; failures manifest as APIPA 169.254.0.0/16 or missing default route."
        ),
        GlossaryEntry(
            id = "mac_address",
            term = "MAC Address",
            fullName = "Media Access Control Address",
            category = GlossaryCategory.FUNDAMENTALS,
            plainEnglishDefinition = "A hardware-level identifier printed into a network chip (written as six pairs like AA:BB:CC:11:22:33).",
            technicalExplanation = "48-bit Layer-2 Ethernet/802.11 frame address. Modern Android randomizes per-SSID client MAC addresses by default for privacy and restricts raw MAC access.",
            appExample = "Shown on discovered Wi-Fi access point BSSIDs and automatically redacted from exported reports when privacy redaction is active.",
            whyItMatters = "Helps identify physical access points vs repeaters and verify switch/router client tables while respecting customer privacy.",
            conciseReference = "48-bit Layer-2 address; Android restricts client hardware MAC visibility and randomizes per-network MACs."
        ),
        GlossaryEntry(
            id = "bssid",
            term = "BSSID",
            fullName = "Basic Service Set Identifier",
            category = GlossaryCategory.FUNDAMENTALS,
            plainEnglishDefinition = "The unique radio address of one specific Wi-Fi access point radio, useful when multiple units share the same Wi-Fi name.",
            technicalExplanation = "48-bit Layer-2 MAC identifier of an individual 802.11 AP radio interface (separate per band/virtual SSID).",
            appExample = "Displayed for each AP in Wi-Fi Scanner, Network Info Wi-Fi details, and Signal Mapper; redacted on report exports.",
            whyItMatters = "Distinguishes which exact ceiling AP, mesh node, or extender a device is roamed onto in multi-AP environments.",
            conciseReference = "48-bit MAC identifier of a specific AP radio; distinct from the human-readable SSID."
        ),
        GlossaryEntry(
            id = "ssid",
            term = "SSID",
            fullName = "Service Set Identifier",
            category = GlossaryCategory.FUNDAMENTALS,
            plainEnglishDefinition = "The broadcast Wi-Fi network name you choose when connecting, such as 'Office-Guest'.",
            technicalExplanation = "Up to 32-byte 802.11 network identifier carried in beacon and probe frames. Android requires Location/Nearby Wi-Fi permission and Location Services to read it.",
            appExample = "Displayed in Wi-Fi Scanner, Network Info, and Signal Mapper; redacted to protect customer identity when exporting reports.",
            whyItMatters = "Multiple BSSIDs often share one SSID in mesh or enterprise networks; hidden SSIDs still broadcast BSSID beacons.",
            conciseReference = "802.11 network name (<=32 bytes); Android gates visibility behind runtime Location/Nearby Wi-Fi permissions."
        ),
        GlossaryEntry(
            id = "lan",
            term = "LAN",
            fullName = "Local Area Network",
            category = GlossaryCategory.FUNDAMENTALS,
            plainEnglishDefinition = "Your local home, office, or site network containing devices connected to the same router or switch.",
            technicalExplanation = "Broadcast domain and local Layer-2/Layer-3 subnet (commonly RFC 1918 192.168.0.0/16, 10.0.0.0/8, or 172.16.0.0/12).",
            appExample = "Scanned by LAN Subnet Scanner and tested internally with iPerf3 Client/Server without leaving the site.",
            whyItMatters = "Testing LAN performance separately from internet speed proves whether a bottleneck is inside the building or at the ISP.",
            conciseReference = "Local broadcast domain/subnet; isolate LAN bottlenecks via Gateway Ping, LAN Scanner, and local iPerf3."
        ),
        GlossaryEntry(
            id = "wan",
            term = "WAN",
            fullName = "Wide Area Network",
            category = GlossaryCategory.FUNDAMENTALS,
            plainEnglishDefinition = "The external internet/ISP link outside your local router.",
            technicalExplanation = "Upstream routed network path beyond the customer edge router across ISP access links, metro rings, and internet backbones.",
            appExample = "Evaluated via Public IP lookup, Internet Speed Test, Ping to 8.8.8.8/1.1.1.1, and Traceroute hops beyond the local gateway.",
            whyItMatters = "Separates ISP WAN faults from local Wi-Fi or internal cabling issues.",
            conciseReference = "Upstream ISP/internet path beyond the local edge NAT/gateway."
        ),
        GlossaryEntry(
            id = "vpn",
            term = "VPN",
            fullName = "Virtual Private Network",
            category = GlossaryCategory.FUNDAMENTALS,
            plainEnglishDefinition = "An encrypted tunnel (often on interface tun0) that routes your device traffic securely through another network.",
            technicalExplanation = "Virtual Layer-3 tunnel interface (e.g., WireGuard/IPsec/OpenVPN) that encapsulates traffic, changes DNS/default routes, and alters MTU.",
            appExample = "Detected distinctly on the Home monitor and Network Info (separating VPN virtual interface from underlying Wi-Fi/Cellular physical transport).",
            whyItMatters = "Active VPNs explain altered public IPs, blocked LAN access, extra latency, or suppressed local gateway pings.",
            conciseReference = "TRANSPORT_VPN virtual tunnel interface; affects DNS, MTU, routing, and local subnet visibility."
        ),
        GlossaryEntry(
            id = "nat",
            term = "NAT",
            fullName = "Network Address Translation",
            category = GlossaryCategory.FUNDAMENTALS,
            plainEnglishDefinition = "The router translation process that lets many private devices share a single public internet IP address.",
            technicalExplanation = "Rewrites private source IP/ports to a public WAN IP/port (and reverses inbound replies) using stateful translation tables; carrier-grade NAT uses 100.64.0.0/10.",
            appExample = "Revealed when Local IP (e.g., 192.168.1.40) differs from Public IP in Network Info, or when Traceroute shows 100.64.x.x CGNAT hops.",
            whyItMatters = "Double NAT or CGNAT can break inbound port forwarding (such as reaching a local iPerf3 server from outside).",
            conciseReference = "Stateful IP/port rewriting; watch for CGNAT (100.64.0.0/10) or carrier 464XLAT translation."
        ),
        GlossaryEntry(
            id = "private_ip",
            term = "Private IP Address",
            fullName = "RFC 1918 Private Network Address",
            category = GlossaryCategory.FUNDAMENTALS,
            plainEnglishDefinition = "An internal IP address meant only for local networks (like 192.168.x.x, 10.x.x.x, or 172.16-31.x.x) that isn't directly reachable from the public internet.",
            technicalExplanation = "Reserved non-internet-routable IPv4 blocks defined in RFC 1918: 10.0.0.0/8, 172.16.0.0/12, and 192.168.0.0/16.",
            appExample = "Classified as Private scope in Network Info, Subnet Calculator, and Traceroute D3 Topology nodes.",
            whyItMatters = "Seeing a Private IP on your device confirms local router assignment; seeing one several Traceroute hops out indicates ISP internal routing.",
            conciseReference = "RFC 1918 non-globally-routable ranges (10/8, 172.16/12, 192.168/16)."
        ),
        GlossaryEntry(
            id = "public_ip",
            term = "Public IP Address",
            fullName = "Globally Routable IP Address",
            category = GlossaryCategory.FUNDAMENTALS,
            plainEnglishDefinition = "The internet-facing IP address websites and external servers see when your device connects.",
            technicalExplanation = "Globally unique, internet-routable IPv4 or IPv6 address assigned by an ISP or VPN exit node.",
            appExample = "Retrieved on demand via Network Info ('Public IP Address') and classified as Public scope in Traceroute hops.",
            whyItMatters = "Confirms which ISP egress or VPN exit location your traffic is actually leaving from.",
            conciseReference = "Globally routable egress IP; compare against local interface IP to confirm NAT or VPN exit."
        ),

        // =====================================================================
        // 2. DIAGNOSTIC TERMINOLOGY
        // =====================================================================
        GlossaryEntry(
            id = "ping",
            term = "Ping",
            fullName = "Packet InterNet Groper",
            category = GlossaryCategory.DIAGNOSTICS,
            plainEnglishDefinition = "A quick check that sends small test probes to a destination and times how long each reply takes.",
            technicalExplanation = "Measures round-trip reachability and packet loss using ICMP Echo Request/Reply (or TCP connect handshake fallback when ICMP is blocked).",
            appExample = "Run from Home -> Ping Test with Auto, ICMP Only, and Socket (TCP) modes plus a live D3 latency chart.",
            whyItMatters = "Primary pass/fail triage tool for checking whether a host is reachable and whether latency spikes or packets drop.",
            conciseReference = "RTT and loss probe via ICMP Echo or TCP SYN handshake fallback."
        ),
        GlossaryEntry(
            id = "icmp",
            term = "ICMP",
            fullName = "Internet Control Message Protocol",
            category = GlossaryCategory.DIAGNOSTICS,
            plainEnglishDefinition = "The network's diagnostic and status signaling language used by Ping and Traceroute error replies.",
            technicalExplanation = "Layer-3 control protocol carrying Echo Request/Reply (types 8/0 in v4, 128/129 in v6), Time Exceeded (TTL=0), and Destination Unreachable messages.",
            appExample = "Used by Ping Test ('ICMP Only' / 'Auto') and Traceroute TTL probes.",
            whyItMatters = "Some routers or firewalls drop ICMP even while HTTPS traffic works—knowing that prevents false 'server down' conclusions.",
            conciseReference = "Layer-3 control messages (Echo, Time Exceeded, Unreachable); frequently rate-limited on edge routers."
        ),
        GlossaryEntry(
            id = "packet",
            term = "Packet",
            fullName = "Network Data Packet",
            category = GlossaryCategory.DIAGNOSTICS,
            plainEnglishDefinition = "A small chunk of data packaged with sender and destination addresses so it can travel across a network.",
            technicalExplanation = "Layer-3 Protocol Data Unit (PDU) containing IP header metadata (source, destination, TTL) and a transport payload.",
            appExample = "Counted per probe in Ping Test ('Packets: X sent, Y received') and Test History CSV/RTF logs.",
            whyItMatters = "Understanding packet-level drops and timeouts separates transient congestion from hard link disconnects.",
            conciseReference = "Layer-3 IP PDU carrying header fields (TTL, DSCP, addresses) and L4 payload."
        ),
        GlossaryEntry(
            id = "packet_loss",
            term = "Packet Loss",
            fullName = "Packet Loss Percentage",
            category = GlossaryCategory.DIAGNOSTICS,
            plainEnglishDefinition = "The percentage of test packets sent that never received a reply back.",
            technicalExplanation = "Ratio of unanswered probes to total transmitted probes ((sent - received) / sent * 100%). Can arise from RF interference, congestion, or firewall filtering.",
            appExample = "Calculated live in Ping Test summary and saved into Diagnostic Test History records.",
            whyItMatters = "Even 2-5% packet loss degrades VoIP audio, video calls, and TCP throughput dramatically.",
            conciseReference = "Unanswered probe ratio; validate against both ICMP rate-limiting and RF/queue drops."
        ),
        GlossaryEntry(
            id = "latency",
            term = "Latency",
            fullName = "Network Delay / Response Time",
            category = GlossaryCategory.DIAGNOSTICS,
            plainEnglishDefinition = "How long it takes (in milliseconds) for a signal to travel to a destination and trigger a response.",
            technicalExplanation = "Propagation, serialization, switching, and queuing delay accumulated along the forward and return path.",
            appExample = "Measured in Ping Test, Gateway Diagnostics, DNS Lookup, Traceroute, and Speed Test ('PING / LATENCY').",
            whyItMatters = "High latency makes interactive calls, gaming, and remote desktop feel sluggish even when download Mbps looks high.",
            conciseReference = "One-way or round-trip delay in ms; bufferbloat and cellular scheduling raise latency under load."
        ),
        GlossaryEntry(
            id = "rtt",
            term = "RTT",
            fullName = "Round-Trip Time",
            category = GlossaryCategory.DIAGNOSTICS,
            plainEnglishDefinition = "The exact stopwatch time from sending a test probe until the reply comes all the way back.",
            technicalExplanation = "Elapsed milliseconds between transmitting a probe packet and receiving its Echo Reply, TCP SYN-ACK, or HTTP response.",
            appExample = "Reported per packet and as min/avg/max/mdev in Ping Test, per hop in Traceroute, and per probe in Speed Test.",
            whyItMatters = "Comparing Gateway RTT against Internet RTT immediately shows whether delay happens inside the LAN/Wi-Fi or on the WAN.",
            conciseReference = "Two-way probe duration (ms); note asymmetric routing can affect intermediate hop RTT."
        ),
        GlossaryEntry(
            id = "jitter",
            term = "Jitter",
            fullName = "Packet Delay Variation (RFC 3550)",
            category = GlossaryCategory.DIAGNOSTICS,
            plainEnglishDefinition = "How much response times bounce up and down from one probe to the next.",
            technicalExplanation = "Computed in Speed Test via RFC 3550 mean consecutive RTT difference, and approximated in Ping via mdev (standard deviation).",
            appExample = "Shown on the Speed Test JITTER card (Stable / Moderate / Variable) and Ping summary (mdev).",
            whyItMatters = "High jitter causes robotic/choppy voice calls and stuttering video streams even when average latency looks decent.",
            conciseReference = "Mean |RTT[i] - RTT[i-1]| (RFC 3550); <5 ms stable, >15 ms risks VoIP disruption."
        ),
        GlossaryEntry(
            id = "ttl",
            term = "TTL",
            fullName = "Time To Live (IPv4) / Hop Limit (IPv6)",
            category = GlossaryCategory.DIAGNOSTICS,
            plainEnglishDefinition = "A hop countdown inside each packet that stops lost packets from looping forever—and lets Traceroute discover each router on the path.",
            technicalExplanation = "An 8-bit header counter decremented by 1 at every Layer-3 router. When TTL reaches 0, the router drops the packet and emits ICMP Time Exceeded.",
            appExample = "Used directly in Traceroute (probing TTL 1..max) and displayed on Ping replies; also appears in DNS records as cache lifetime (seconds).",
            whyItMatters = "Without TTL increments, Traceroute could not map intermediate hops; in DNS, low TTL speeds up failover.",
            conciseReference = "IP packet hop counter (decremented per L3 router) OR DNS record cache duration in seconds."
        ),
        GlossaryEntry(
            id = "hop",
            term = "Hop",
            fullName = "Network Router Hop",
            category = GlossaryCategory.DIAGNOSTICS,
            plainEnglishDefinition = "One router step along the journey between your phone and a destination server.",
            technicalExplanation = "A Layer-3 routing node where packet TTL/Hop Limit decrements by 1 as traffic crosses between subnets or autonomous systems.",
            appExample = "Each numbered circle in Traceroute D3 Topology and each row in the Traceroute Hop List represents one hop.",
            whyItMatters = "Pinpoints where traffic slows down, loops, crosses CGNAT/464XLAT translation, or gets filtered.",
            conciseReference = "Single L3 router forwarding step; intermediate timeouts (* * *) do not necessarily break end-to-end delivery."
        ),
        GlossaryEntry(
            id = "traceroute",
            term = "Traceroute",
            fullName = "Path Discovery Trace",
            category = GlossaryCategory.DIAGNOSTICS,
            plainEnglishDefinition = "A path-mapping test that reveals the chain of routers between your device and a target.",
            technicalExplanation = "Incrementally probes TTL 1, 2, 3... via ping/traceroute probes and records ICMP Time Exceeded responders until the target replies.",
            appExample = "Built into the Traceroute Path Hops tool with an interactive D3 graph, IP scope badges (Private/CGNAT/Reserved/Public), and RTF export.",
            whyItMatters = "Shows whether packets stall inside the LAN, carrier core, or remote ISP network.",
            conciseReference = "TTL-stepped L3 path probe; routers may suppress or rate-limit control-plane ICMP replies."
        ),
        GlossaryEntry(
            id = "timeout",
            term = "Timeout",
            fullName = "Probe Response Timeout",
            category = GlossaryCategory.DIAGNOSTICS,
            plainEnglishDefinition = "When a test waits for a set time limit and hears nothing back—often shown as * * * in Traceroute.",
            technicalExplanation = "Wait threshold expired before receiving an ICMP, DNS, or TCP reply. May indicate silent firewall drops, asymmetric return paths, or an offline host.",
            appExample = "Shown as Request Timed Out (* * *) in Traceroute hops or socket/connect timeout diagnostics in Ping, DNS, and iPerf3.",
            whyItMatters = "A single timed-out middle hop is normal if later hops respond; consecutive timeouts ending before the target indicate a real block.",
            conciseReference = "No reply within wait window; distinguish control-plane ICMP suppression from actual forwarding blackholes."
        ),
        GlossaryEntry(
            id = "dns_resolution",
            term = "DNS Resolution",
            fullName = "Hostname Resolution",
            category = GlossaryCategory.DIAGNOSTICS,
            plainEnglishDefinition = "The process of looking up a domain name like google.com to find its IP address before connecting.",
            technicalExplanation = "Query/response cycle from stub resolver to system DNS or DoH/DoT resolver returning resource records (or NXDOMAIN/SERVFAIL errors).",
            appExample = "Timed at the start of Ping Test ('DNS Resolved: host -> ip') and tested in detail in DNS Lookup & Diagnostics.",
            whyItMatters = "Slow or failing DNS resolution makes web pages hang before a single byte of page traffic even starts.",
            conciseReference = "Stub-to-recursive lookup; DNS success proves name lookup only, not TCP/ICMP reachability to the target."
        ),
        GlossaryEntry(
            id = "dns_record",
            term = "DNS Record",
            fullName = "Domain Name System Resource Record",
            category = GlossaryCategory.DIAGNOSTICS,
            plainEnglishDefinition = "An individual entry stored in DNS for a domain—such as its IPv4 address, IPv6 address, alias, or mail server.",
            technicalExplanation = "Structured DNS database entry consisting of Name, Type (A, AAAA, CNAME, MX, TXT, NS, PTR), TTL, and Data payload.",
            appExample = "Selected by record-type chips inside DNS Lookup & Diagnostics.",
            whyItMatters = "Querying the specific record type confirms mail routing, IPv6 readiness, canonical aliases, or reverse PTR identity.",
            conciseReference = "Typed zone resource entries (A, AAAA, CNAME, MX, TXT, NS, PTR) queried via system or DoH."
        ),
        GlossaryEntry(
            id = "a_record",
            term = "A Record",
            fullName = "IPv4 Address Record",
            category = GlossaryCategory.DIAGNOSTICS,
            plainEnglishDefinition = "The DNS record that maps a website or host name to its IPv4 address.",
            technicalExplanation = "DNS resource record type A returning a 32-bit IPv4 address for a queried hostname.",
            appExample = "Queried using the 'A' record chip in DNS Lookup & Diagnostics.",
            whyItMatters = "Verifies that IPv4 clients can resolve the right server IP.",
            conciseReference = "Hostname -> 32-bit IPv4 mapping record."
        ),
        GlossaryEntry(
            id = "aaaa_record",
            term = "AAAA Record",
            fullName = "IPv6 Address Record",
            category = GlossaryCategory.DIAGNOSTICS,
            plainEnglishDefinition = "The DNS record that maps a host name to its IPv6 address.",
            technicalExplanation = "DNS resource record type AAAA ('quad-A') returning a 128-bit IPv6 address.",
            appExample = "Queried using the 'AAAA' record chip in DNS Lookup & Diagnostics.",
            whyItMatters = "Missing or stale AAAA records cause dual-stack clients to either delay connections or reach an unintended server.",
            conciseReference = "Hostname -> 128-bit IPv6 mapping record."
        ),
        GlossaryEntry(
            id = "cname_record",
            term = "CNAME",
            fullName = "Canonical Name Record",
            category = GlossaryCategory.DIAGNOSTICS,
            plainEnglishDefinition = "A DNS alias that points one domain name to another domain name (like www.example.com -> cdn.provider.net).",
            technicalExplanation = "Resource record aliasing a subdomain to a canonical FQDN, triggering chained resolution to the target's A/AAAA records.",
            appExample = "Queried with the 'CNAME' chip in DNS Lookup & Diagnostics via Cloudflare/Google DoH.",
            whyItMatters = "Exposes CDN routing, SaaS custom domains, and broken alias chains.",
            conciseReference = "DNS alias pointing a name to another canonical FQDN."
        ),
        GlossaryEntry(
            id = "mx_record",
            term = "MX Record",
            fullName = "Mail Exchange Record",
            category = GlossaryCategory.DIAGNOSTICS,
            plainEnglishDefinition = "The DNS record that specifies which mail servers receive email for a domain, along with priority order.",
            technicalExplanation = "Mail routing resource record containing a numeric preference value (lower = higher priority) and mail server hostname.",
            appExample = "Queried using the 'MX' chip in DNS Lookup & Diagnostics.",
            whyItMatters = "Quickly verifies domain email delivery configuration without needing external desktop tools.",
            conciseReference = "Mail routing host + priority preference value."
        ),

        // =====================================================================
        // 3. WI-FI TERMINOLOGY
        // =====================================================================
        GlossaryEntry(
            id = "rssi",
            term = "RSSI",
            fullName = "Received Signal Strength Indicator",
            category = GlossaryCategory.WIFI,
            plainEnglishDefinition = "How loud the Wi-Fi radio signal arrives at your phone, measured as a negative dBm number.",
            technicalExplanation = "802.11 received RF power level in dBm (typically -30 dBm close to the AP down to -90 dBm near disconnection). Every 3 dB drop halves signal power.",
            appExample = "Shown live in Network Info Wi-Fi Telemetry, Wi-Fi Scanner, Live Signal Graph, and Signal Mapper heatmaps.",
            whyItMatters = "Identifies dead zones, weak wall penetration, and whether a client has enough signal for stable modulation.",
            conciseReference = "Received RF power in dBm; logarithmic (-3 dB = half power, -10 dB = 10x weaker)."
        ),
        GlossaryEntry(
            id = "dbm",
            term = "dBm",
            fullName = "Decibels Relative to One Milliwatt",
            category = GlossaryCategory.WIFI,
            plainEnglishDefinition = "The negative unit used to measure Wi-Fi signal power; numbers closer to 0 (like -45 dBm) are stronger than -80 dBm.",
            technicalExplanation = "Logarithmic RF power unit referenced to 1 mW: 0 dBm = 1 mW, -30 dBm = 0.001 mW, -60 dBm = 10^-6 mW.",
            appExample = "Displayed on every AP card in Wi-Fi Scanner, live telemetry gauges, and Signal Mapper sample pins.",
            whyItMatters = "Because dBm is logarithmic, moving from -75 dBm to -65 dBm is a huge 10x improvement in received signal power.",
            conciseReference = "Logarithmic mW power scale; target >= -67 dBm for voice/video, >= -65 dBm for high-rate clients."
        ),
        GlossaryEntry(
            id = "signal_strength",
            term = "Signal Strength",
            fullName = "Wi-Fi Signal Classification",
            category = GlossaryCategory.WIFI,
            plainEnglishDefinition = "Easy-to-read labels (Excellent, Good, Fair, Weak, Dead Zone) translating raw dBm readings into practical quality tiers.",
            technicalExplanation = "Thresholded RF quality bands mapped across -30 dBm ceiling to -100 dBm floor (e.g., Excellent >= -55 dBm, Good >= -67 dBm, Fair >= -75 dBm).",
            appExample = "Color-coded badges across Network Info, Wi-Fi Scanner, and Signal Mapper dead-zone analysis.",
            whyItMatters = "Gives field technicians and customers an immediate visual standard for room-by-room coverage acceptability.",
            conciseReference = "Standardized RSSI bands used to color-code coverage and locate weak spots."
        ),
        GlossaryEntry(
            id = "channel",
            term = "Channel",
            fullName = "Wi-Fi RF Channel",
            category = GlossaryCategory.WIFI,
            plainEnglishDefinition = "The specific radio lane inside a Wi-Fi band—like lanes on a highway—that an access point broadcasts on.",
            technicalExplanation = "Center frequency index within 2.4 GHz (ch 1-14; only 1, 6, 11 don't overlap at 20 MHz), 5 GHz (UNII channels), or 6 GHz (PSC/standard channels).",
            appExample = "Listed per access point in Wi-Fi Scanner and Network Info Wi-Fi details.",
            whyItMatters = "Too many neighboring routers stacked on the same or overlapping channel cause contention, retries, and sluggish speeds.",
            conciseReference = "2.4 GHz non-overlapping: 1/6/11 (20 MHz); 5/6 GHz provide wider non-overlapping spectrum."
        ),
        GlossaryEntry(
            id = "channel_width",
            term = "Channel Width",
            fullName = "RF Channel Bandwidth (20 / 40 / 80 / 160 / 320 MHz)",
            category = GlossaryCategory.WIFI,
            plainEnglishDefinition = "How wide the radio lane is: wider channels (like 80 MHz) carry faster speeds, while narrower 20 MHz channels avoid interference better.",
            technicalExplanation = "Bonded 20 MHz sub-channel spectrum width. Doubling width roughly doubles theoretical PHY throughput while raising noise floor by ~3 dB and overlap risk.",
            appExample = "Reported per scanned AP and connected Wi-Fi link inside Wi-Fi Scanner and Network Info.",
            whyItMatters = "40 MHz on 2.4 GHz usually harms apartments; 80 MHz on 5 GHz balances speed and neighbor reuse.",
            conciseReference = "20/40/80/160/320 MHz modulation span; +3 dB noise penalty per width doubling."
        ),
        GlossaryEntry(
            id = "band_2_4_ghz",
            term = "2.4 GHz",
            fullName = "2.4 GHz ISM Wi-Fi Band",
            category = GlossaryCategory.WIFI,
            plainEnglishDefinition = "The longest-range Wi-Fi frequency band that passes through walls well, but has fewer lanes and gets crowded easily.",
            technicalExplanation = "802.11b/g/n/ax band (~2412-2484 MHz) offering only three non-overlapping 20 MHz channels (1, 6, 11) and shared with Bluetooth/IoT devices.",
            appExample = "Filterable via the '2.4 GHz' band chip inside Wi-Fi Scanner.",
            whyItMatters = "Ideal for distant corners or legacy IoT devices, but overcrowded channels quickly cripple throughput.",
            conciseReference = "Longer reach, 3 clean 20 MHz channels (1/6/11), high non-Wi-Fi & neighbor congestion."
        ),
        GlossaryEntry(
            id = "band_5_ghz",
            term = "5 GHz",
            fullName = "5 GHz UNII Wi-Fi Band",
            category = GlossaryCategory.WIFI,
            plainEnglishDefinition = "A faster, cleaner Wi-Fi band with many non-overlapping channels, though its signal drops faster through thick walls.",
            technicalExplanation = "802.11a/n/ac/ax UNII-1..3 bands (~5180-5825 MHz) supporting 20/40/80/160 MHz channels (with DFS radar sharing in UNII-2).",
            appExample = "Filterable via the '5 GHz' chip inside Wi-Fi Scanner and visible in AP channel readouts.",
            whyItMatters = "Primary workhorse band for high-speed laptops and phones when AP placement is within 1-2 walls.",
            conciseReference = "Primary high-throughput band; watch DFS radar events and wall attenuation vs 2.4 GHz."
        ),
        GlossaryEntry(
            id = "band_6_ghz",
            term = "6 GHz",
            fullName = "6 GHz Wi-Fi 6E / Wi-Fi 7 Band",
            category = GlossaryCategory.WIFI,
            plainEnglishDefinition = "The newest, ultra-wide Wi-Fi highway for Wi-Fi 6E and Wi-Fi 7 devices, free from legacy slow devices.",
            technicalExplanation = "5925-7125 MHz spectrum (Wi-Fi 6E/7) with abundant 80/160/320 MHz channels, mandatory WPA3/OWE security, and shorter range.",
            appExample = "Identifiable in Wi-Fi Scanner via the '6 GHz' band filter and Wi-Fi 6E/7 standard badges.",
            whyItMatters = "Provides clean multi-gigabit short-range links in dense apartments and offices when client & AP both support 6E/7.",
            conciseReference = "Wi-Fi 6E/7 spectrum; mandatory Protected Management Frames/WPA3, highest attenuation."
        ),
        GlossaryEntry(
            id = "interference",
            term = "Interference",
            fullName = "Co-Channel & Adjacent-Channel RF Interference",
            category = GlossaryCategory.WIFI,
            plainEnglishDefinition = "Radio noise from neighboring Wi-Fi routers or wireless electronics competing on the same airwaves and slowing traffic down.",
            technicalExplanation = "Co-channel contention (forcing CSMA/CA airtime deferrals) or overlapping adjacent-channel energy (causing frame corruption and retransmissions).",
            appExample = "Diagnosed in Wi-Fi Scanner by inspecting how many nearby BSSIDs share your channel or overlap channels 1-11.",
            whyItMatters = "A router can show -50 dBm 'full bars' and still feel slow if severe interference forces constant packet retries.",
            conciseReference = "Airtime contention (co-channel) or corrupted frames (ACI); non-1/6/11 channels on 2.4 GHz cause ACI."
        ),
        GlossaryEntry(
            id = "noise",
            term = "Noise",
            fullName = "RF Noise Floor",
            category = GlossaryCategory.WIFI,
            plainEnglishDefinition = "Background radio static in the environment (typically around -95 dBm) that Wi-Fi signals have to rise above to be understood clearly.",
            technicalExplanation = "Combined thermal noise and ambient RF energy measured by the wireless driver (/proc/net/wireless). Android hides this metric on many devices.",
            appExample = "Read directly from hardware/driver stats when exposed, or marked honestly as 'Unavailable' when Android/device drivers omit it.",
            whyItMatters = "High background noise shrinks usable Signal-to-Noise Ratio even near the router.",
            conciseReference = "Ambient RF floor (dBm); app strictly displays Unavailable if kernel/driver omits noise telemetry."
        ),
        GlossaryEntry(
            id = "snr",
            term = "SNR",
            fullName = "Signal-to-Noise Ratio",
            category = GlossaryCategory.WIFI,
            plainEnglishDefinition = "How much stronger the Wi-Fi signal is compared to the background radio noise—higher dB means cleaner, faster speeds.",
            technicalExplanation = "Differential calculated as RSSI (dBm) minus Noise Floor (dBm), expressed in dB. Target >= 25 dB for data, >= 35 dB for top modulation.",
            appExample = "Displayed alongside RSSI in Network Info and Signal Mapper whenever the device driver exposes noise floor.",
            whyItMatters = "Predicts real wireless link stability and achievable MCS modulation rates far better than RSSI alone.",
            conciseReference = "RSSI - Noise (dB); >=25 dB reliable, >=35 dB high MCS rates; shown Unavailable if noise unexposed."
        ),
        GlossaryEntry(
            id = "access_point",
            term = "Access Point",
            fullName = "802.11 Wireless Access Point (AP)",
            category = GlossaryCategory.WIFI,
            plainEnglishDefinition = "The Wi-Fi transmitter (built into a router, ceiling AP, or mesh unit) that connects wireless devices to the wired network.",
            technicalExplanation = "Layer-2 bridge radiating one or more BSSIDs per frequency band between 802.11 wireless clients and Ethernet distribution.",
            appExample = "Each row in Wi-Fi Scanner represents a discovered AP radio; Signal Mapper recommends AP placement based on room surveys.",
            whyItMatters = "Verifying AP count, band, and placement prevents dead zones and sticky-client roaming problems.",
            conciseReference = "802.11 L2 wireless-to-wired bridge; inspect per-BSSID RSSI and channel load."
        ),
        GlossaryEntry(
            id = "wifi_security",
            term = "Wi-Fi Security",
            fullName = "802.11 Authentication & Encryption",
            category = GlossaryCategory.WIFI,
            plainEnglishDefinition = "The lock protecting a Wi-Fi network from eavesdropping or unauthorized access (such as WPA2 or WPA3).",
            technicalExplanation = "Capabilities parsed from AP beacon RSN/WPA information elements including Open, WEP/TKIP legacy, WPA2-PSK/Enterprise, WPA3-SAE, and WPS.",
            appExample = "Parsed and filterable via security chips in Wi-Fi Scanner.",
            whyItMatters = "Open or misconfigured legacy security exposes client traffic and can prevent modern devices from joining securely.",
            conciseReference = "RSN security suites (Open, WPA2-AES, WPA3-SAE, Mixed, WPS warning)."
        ),
        GlossaryEntry(
            id = "wpa2",
            term = "WPA2",
            fullName = "Wi-Fi Protected Access 2",
            category = GlossaryCategory.WIFI,
            plainEnglishDefinition = "The widely compatible Wi-Fi encryption standard used on almost all modern routers and IoT devices.",
            technicalExplanation = "IEEE 802.11i security using AES-CCMP encryption with Pre-Shared Key (PSK) or 802.1X Enterprise authentication.",
            appExample = "Shown as 'WPA2' or 'WPA2/WPA3 Mixed' on cards and filter chips in Wi-Fi Scanner.",
            whyItMatters = "Good baseline compatibility, though WPA2-PSK is weaker against offline dictionary attacks than WPA3-SAE.",
            conciseReference = "802.11i AES-CCMP; widely compatible, superseded in resilience by WPA3-SAE."
        ),
        GlossaryEntry(
            id = "wpa3",
            term = "WPA3",
            fullName = "Wi-Fi Protected Access 3",
            category = GlossaryCategory.WIFI,
            plainEnglishDefinition = "The newest Wi-Fi security standard with stronger password protection and mandatory support on 6 GHz networks.",
            technicalExplanation = "Replaces 4-way PSK handshake vulnerability with Simultaneous Authentication of Equals (SAE) plus mandatory Protected Management Frames (PMF).",
            appExample = "Identified and filterable via 'WPA3' tags in Wi-Fi Scanner.",
            whyItMatters = "Required for 6 GHz Wi-Fi 6E/7; note that some older printers or IoT gadgets may fail to join WPA3-only SSIDs unless Mixed mode is enabled.",
            conciseReference = "SAE handshake + mandatory PMF (802.11w); mandatory for 6 GHz SSIDs."
        ),

        // =====================================================================
        // 4. PERFORMANCE TERMINOLOGY
        // =====================================================================
        GlossaryEntry(
            id = "mbps",
            term = "Mbps",
            fullName = "Megabits Per Second",
            category = GlossaryCategory.PERFORMANCE,
            plainEnglishDefinition = "The standard unit for measuring internet and network speed—1,000,000 bits transferred each second.",
            technicalExplanation = "Decimal bit rate (10^6 bits/s). Divide Mbps by ~8 to estimate theoretical peak file size speed in Megabytes per second (MB/s).",
            appExample = "Primary unit in Internet Speed Test gauge/cards and iPerf3 throughput intervals/summaries.",
            whyItMatters = "Confusing Mbps (bits) with MB/s (bytes, 8x larger) is one of the most common customer misunderstandings.",
            conciseReference = "10^6 bits/sec; ~8 Mbps ~= 1 MiB/s payload rate before protocol overhead."
        ),
        GlossaryEntry(
            id = "gbps",
            term = "Gbps",
            fullName = "Gigabits Per Second",
            category = GlossaryCategory.PERFORMANCE,
            plainEnglishDefinition = "A super-fast speed unit equal to 1,000 Mbps—common on fiber internet and wired Ethernet backbones.",
            technicalExplanation = "10^9 bits per second. Typical client Wi-Fi PHY rates exceed 1 Gbps on paper while real TCP goodput hits 40-70% of PHY.",
            appExample = "Relevant when comparing gigabit ISP plans against Wi-Fi link speed vs Speed Test Mbps.",
            whyItMatters = "Explains why a '1.2 Gbps' Wi-Fi link speed often delivers 500-750 Mbps in real TCP Speed Tests.",
            conciseReference = "1,000 Mbps (10^9 bps); Wi-Fi TCP goodput typically realizes ~45-70% of PHY link rate."
        ),
        GlossaryEntry(
            id = "throughput",
            term = "Throughput",
            fullName = "Measured Effective Data Rate",
            category = GlossaryCategory.PERFORMANCE,
            plainEnglishDefinition = "The actual real-world speed your data achieved during a test, after overhead and network conditions.",
            technicalExplanation = "Measured payload bytes successfully delivered per unit time across L4/L7 (TCP goodput or HTTP transfer rate).",
            appExample = "Measured directly in Internet Speed Test (HTTP) and iPerf3 Client/Server (TCP stream).",
            whyItMatters = "Throughput is what users experience—far more meaningful than theoretical 'up to' Wi-Fi PHY link speeds.",
            conciseReference = "Actual payload bit rate achieved; always lower than theoretical L1/L2 PHY rate."
        ),
        GlossaryEntry(
            id = "bandwidth",
            term = "Bandwidth",
            fullName = "Link Capacity / Channel Capacity",
            category = GlossaryCategory.PERFORMANCE,
            plainEnglishDefinition = "The maximum theoretical data capacity of a connection, like the width of a pipe before congestion.",
            technicalExplanation = "Used in networking both for maximum data-rate capacity (Mbps/Gbps) and RF spectrum span (MHz); available bandwidth limits peak throughput.",
            appExample = "Tracked across intervals in iPerf3 ('Interval Throughput') and compared against ISP plan speeds in Speed Test.",
            whyItMatters = "Comparing measured throughput to subscribed/link bandwidth reveals bottlenecks, throttling, or interference.",
            conciseReference = "Maximum capacity of link/spectrum; real-world throughput is bounded by bottleneck link bandwidth."
        ),
        GlossaryEntry(
            id = "download",
            term = "Download",
            fullName = "Downstream Data Transfer (Server -> Device)",
            category = GlossaryCategory.PERFORMANCE,
            plainEnglishDefinition = "How fast data comes from the internet or server to your phone (streaming video, loading sites, receiving files).",
            technicalExplanation = "Inbound data transfer from remote endpoint to client. Triggered via HTTP GET in Speed Test and '-R' Reverse mode in iPerf3.",
            appExample = "Measured in Speed Test DOWNLOAD phase and iPerf3 Download (-R) Reverse mode.",
            whyItMatters = "Most residential internet plans are asymmetric—much faster Download than Upload.",
            conciseReference = "Server-to-client direction; Speed Test GET stream or iPerf3 '-R' reverse mode."
        ),
        GlossaryEntry(
            id = "upload",
            term = "Upload",
            fullName = "Upstream Data Transfer (Device -> Server)",
            category = GlossaryCategory.PERFORMANCE,
            plainEnglishDefinition = "How fast your phone can send data out to a server (video calls, cloud backups, sending large photos).",
            technicalExplanation = "Outbound transmission from client to remote endpoint. Often lower on cable/cellular links and prone to carrier shaping or buffer stalls.",
            appExample = "Measured in Speed Test UPLOAD phase and iPerf3 default Client Upload (Send) mode.",
            whyItMatters = "Poor upload breaks video calls, security camera feeds, and cloud backups even when download looks great.",
            conciseReference = "Client-to-server direction; susceptible to asymmetrical ISP plans, PMTUD blackholes, and buffer stall."
        ),
        GlossaryEntry(
            id = "peak_throughput",
            term = "Peak Throughput",
            fullName = "Highest Interval Speed",
            category = GlossaryCategory.PERFORMANCE,
            plainEnglishDefinition = "The fastest burst speed measured during a single short sample window of the test.",
            technicalExplanation = "Maximum observed throughput across sampling intervals (150 ms windows in Speed Test; 1-second windows in iPerf3).",
            appExample = "Displayed as 'Peak: X.X Mbps' on Speed Test cards/reports and 'Peak Bandwidth' on iPerf3 summaries.",
            whyItMatters = "Large gap between Peak and Average suggests unstable Wi-Fi, competing traffic, or TCP congestion stalls.",
            conciseReference = "Max single-window Mbps; compare with Average Mbps to spot instability or buffer bursts."
        ),
        GlossaryEntry(
            id = "average_throughput",
            term = "Average Throughput",
            fullName = "Sustained Mean Speed",
            category = GlossaryCategory.PERFORMANCE,
            plainEnglishDefinition = "Your steady overall speed averaged across the whole duration of the test.",
            technicalExplanation = "Total payload bytes transferred divided by total elapsed test duration converted into Mbps.",
            appExample = "Displayed as the primary big Mbps number in Speed Test and 'Average Bandwidth' in iPerf3.",
            whyItMatters = "Represents sustained real-world transfer capability much more reliably than brief initial bursts.",
            conciseReference = "totalBits / totalDurationSeconds; guards against initial socket-buffer burst illusions."
        ),
        GlossaryEntry(
            id = "tcp",
            term = "TCP",
            fullName = "Transmission Control Protocol",
            category = GlossaryCategory.PERFORMANCE,
            plainEnglishDefinition = "The reliable delivery method used for websites, file transfers, and iPerf3 tests—it checks every packet and resends anything dropped.",
            technicalExplanation = "Connection-oriented Layer-4 transport providing ordered delivery, acknowledgments, flow control (receive window), and congestion control.",
            appExample = "Used by Socket Ping (TCP connect), Internet Speed Test (HTTP/TLS over TCP), and iPerf3 Client/Server streams.",
            whyItMatters = "Because TCP slows down whenever packets drop or latency spikes, packet loss hurts TCP speed even on fast links.",
            conciseReference = "Reliable L4 stream; throughput sensitive to RTT * loss (Mathis) and Path MTU blackholes."
        ),
        GlossaryEntry(
            id = "udp",
            term = "UDP",
            fullName = "User Datagram Protocol",
            category = GlossaryCategory.PERFORMANCE,
            plainEnglishDefinition = "A lightweight 'fire-and-forget' delivery method used by DNS lookups, voice calls, and live video where speed beats resending old packets.",
            technicalExplanation = "Connectionless Layer-4 transport with minimal 8-byte header and no built-in retransmission or congestion backoff.",
            appExample = "Used underneath system DNS lookups; note that the built-in iPerf3 engine in this app currently implements TCP benchmarking.",
            whyItMatters = "Understanding TCP vs UDP explains why DNS/VoIP degrade with jitter while TCP file transfers slow down with retransmits.",
            conciseReference = "Connectionless L4 datagrams (DNS, VoIP); app's iPerf3 engine currently supports TCP only."
        ),
        GlossaryEntry(
            id = "iperf3",
            term = "iPerf3",
            fullName = "ESnet iPerf3 Bandwidth Measurement Protocol",
            category = GlossaryCategory.PERFORMANCE,
            plainEnglishDefinition = "A pro-grade speed tool that tests pure network throughput directly between your phone and a specific local or remote test server.",
            technicalExplanation = "Client/server active benchmark protocol exchanging JSON parameters over a TCP control channel before driving parallel TCP data streams.",
            appExample = "Implemented natively in iPerf3 Client / Server mode with 1-second interval reporting, control monitoring, and Reverse (-R) support.",
            whyItMatters = "Unlike internet Speed Tests, iPerf3 lets you test internal Wi-Fi/LAN speed to your own server without ISP limits.",
            conciseReference = "ESnet TCP control+data stream protocol; separates internal Wi-Fi/LAN capacity from WAN."
        ),
        GlossaryEntry(
            id = "parallel_streams",
            term = "Parallel Streams",
            fullName = "Concurrent TCP Data Connections (-P)",
            category = GlossaryCategory.PERFORMANCE,
            plainEnglishDefinition = "Running multiple simultaneous transfer lanes at once so a single connection's speed limit doesn't hold back a fast link.",
            technicalExplanation = "Opens N concurrent TCP data sockets tied to the iPerf3 control session cookie to saturate high Bandwidth-Delay Product (BDP) paths.",
            appExample = "Configurable from 1 to 8 streams via the 'Parallel Streams' selector in iPerf3 Client mode.",
            whyItMatters = "On high-latency or gigabit links, 4 streams often uncover bandwidth that a single TCP socket cannot fill alone.",
            conciseReference = "Multiple concurrent TCP sockets (1-8 in app); overcomes per-socket BDP/window ceilings."
        ),
        GlossaryEntry(
            id = "buffer",
            term = "Buffer",
            fullName = "Socket / Kernel Memory Buffer",
            category = GlossaryCategory.PERFORMANCE,
            plainEnglishDefinition = "A temporary holding area in memory where data sits briefly right before leaving or after arriving on the network.",
            technicalExplanation = "Kernel SO_SNDBUF / SO_RCVBUF memory queues smoothing bursty application writes onto the TCP stack.",
            appExample = "Bounded to 64 KB upload / 256 KB download sockets in iPerf3Engine so interval 0-1s reports real network pacing rather than phantom buffer pre-fills.",
            whyItMatters = "Oversized buffers can create fake initial burst speeds or cause bufferbloat latency spikes under heavy load.",
            conciseReference = "Kernel socket queue (SO_SNDBUF/SO_RCVBUF); affects first-interval burst accuracy & stall diagnosis."
        ),
        GlossaryEntry(
            id = "server",
            term = "Server",
            fullName = "Listening Responder Endpoint",
            category = GlossaryCategory.PERFORMANCE,
            plainEnglishDefinition = "The device waiting on a port (like TCP 5201) to accept incoming test connections from a client.",
            technicalExplanation = "Bound ServerSocket listener accepting control handshakes and peer data streams—built right into the app's iPerf3 Server tab.",
            appExample = "Started via iPerf3 -> Server tab (default port 5201, 2-minute idle auto-stop, stops when leaving foreground).",
            whyItMatters = "Lets two phones—or a laptop running iPerf3 -c and your Android phone—benchmark internal Wi-Fi without extra servers.",
            conciseReference = "Passive TCP listener on port 5201+; auto-stops after 2 min idle or backgrounding in app."
        ),
        GlossaryEntry(
            id = "client",
            term = "Client",
            fullName = "Initiating Test Endpoint",
            category = GlossaryCategory.PERFORMANCE,
            plainEnglishDefinition = "The device that starts the connection by dialing a server's IP address and port.",
            technicalExplanation = "Active initiator connecting to a target host:port to send (Upload) or pull (Download -R) benchmark traffic.",
            appExample = "Operated via iPerf3 -> Client tab with host/IP, port, duration (1-300s), streams (1-8), timeout, and Reverse (-R) toggle.",
            whyItMatters = "All outbound diagnostics (Ping, DNS, Speed Test, iPerf3 Client) act as clients initiating probes to a server.",
            conciseReference = "Active initiator targeting host:port; verifies egress paths and LAN/WAN throughput."
        )
    )

    fun findById(id: String): GlossaryEntry? = entries.firstOrNull { it.id == id }
}
