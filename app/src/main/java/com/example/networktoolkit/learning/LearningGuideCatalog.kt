package com.example.networktoolkit.learning

/**
 * Authoritative offline educational catalog grounded strictly in the real tools,
 * UI controls, protocols, and limitations implemented in Network Technician Toolkit.
 */
object LearningGuideCatalog {

    val tools: List<ToolGuideContent> = listOf(
        // 1. Live Connectivity Monitor
        ToolGuideContent(
            id = "live_connectivity",
            title = "Live Connectivity Monitor",
            subtitle = "Real-time Home header diagnostics for Internet, Transport, VPN & Gateway RTT",
            category = ToolGuideCategory.CORE_DIAGNOSTICS,
            whereToFind = "Home screen -> top 'LIVE CONNECTIVITY DIAGNOSTICS' card",
            whatItDoes = "Continuously summarizes whether Android has validated internet reachability, which active transport and interface is carrying traffic, whether a VPN virtual tunnel is active, and whether the local default gateway answers RTT probes.",
            whenToUse = listOf(
                "As the first 5-second glance whenever you open the app on a customer site.",
                "To verify whether a phone moved back to Wi-Fi, stayed on Cellular, or is behind a captive portal.",
                "To check if an active VPN tunnel is altering routing before running local LAN tests."
            ),
            howToUseSteps = listOf(
                "Open the Home screen and read the four status lines inside LIVE CONNECTIVITY DIAGNOSTICS.",
                "Check the top status line: INTERNET ONLINE (validated), LIMITED / CAPTIVE PORTAL, or NO INTERNET ACCESS.",
                "Verify Connection Type and active interface (for example Wi-Fi wlan0 or Cellular rmnet_data) plus your assigned local IP.",
                "Inspect VPN Status to see whether traffic is routed through a virtual tunnel such as tun0.",
                "Tap REFRESH (or tap the card) to re-probe internet validation and Default Gateway RTT immediately."
            ),
            understandingResults = listOf(
                "INTERNET ONLINE" to "Android NetworkCapabilities reports both NET_CAPABILITY_INTERNET and NET_CAPABILITY_VALIDATED.",
                "LIMITED / CAPTIVE PORTAL" to "The link is associated, but upstream login/captive wall or upstream outage prevents full validation.",
                "Connection Type & Interface" to "Derived from Android ConnectivityManager + NetworkCapabilities transports, never guessed by IP alone.",
                "VPN Status" to "Reports whether TRANSPORT_VPN is active (virtual tunnel) alongside unencapsulated vs tunneled route status.",
                "Default Gateway RTT" to "Local subnet router responsiveness in milliseconds, or 'Routed through VPN tunnel' when VPN intercepts local probes."
            ),
            whatResultsIndicate = listOf(
                "Gateway responsive + No Internet Access" to "Local Wi-Fi/router link works, but WAN modem, ISP link, or upstream DNS/validation is failing.",
                "Gateway unresponsive + Wi-Fi connected" to "Local wireless stall, client isolation, router firewall blocking probes, or wrong subnet lease.",
                "Active VPN + broken LAN access" to "Full-tunnel VPN is likely stealing local subnet routes."
            ),
            troubleshootingScenarios = listOf(
                "Hotel or guest Wi-Fi says connected with no apps loading" to "Look for LIMITED / CAPTIVE PORTAL; open a browser to accept the splash login before running speed tests.",
                "Cellular backup kicked in quietly" to "Connection Type shows Cellular instead of Wi-Fi; fix Wi-Fi association before blaming site ISP speed."
            ),
            limitations = listOf(
                "Gateway RTT reflects control-plane responsiveness; some routers de-prioritize or block local probes cleanly while still routing traffic.",
                "While a full-tunnel VPN is active, local default gateway reachability may appear suppressed by the tunnel."
            ),
            beginnerTip = "Always check the top card first: green Gateway + red Internet means your phone reaches the router fine, so the trouble lies past the router.",
            intermediateGuidance = "Watch VPN Status and Transport together—an active VPN explains unexpected public IPs and suppressed local gateway RTT.",
            advancedReference = "Backed by NetworkConnectivityMonitor querying NetworkCapabilities.NET_CAPABILITY_VALIDATED, LinkProperties routes/interfaces, and gateway probe RTT.",
            quickHelpBullets = listOf(
                "Internet Status uses Android NET_CAPABILITY_VALIDATED (Online, Captive Portal/Limited, or Offline).",
                "Connection Type distinguishes physical Wi-Fi/Cellular/Ethernet from virtual VPN tunnels.",
                "Gateway RTT isolates local router health from upstream ISP problems.",
                "Tap REFRESH or the card anytime to force a fresh probe."
            ),
            relatedGlossaryIds = listOf("ip_address", "default_gateway", "vpn", "rtt", "wan", "lan")
        ),

        // 2. Connection Diagnostics & Network Info
        ToolGuideContent(
            id = "network_info",
            title = "Network Information & Diagnostics",
            subtitle = "Transport, IPv4/IPv6 addressing, Subnet Mask, Gateway, MTU, Public IP & Wi-Fi telemetry",
            category = ToolGuideCategory.CORE_DIAGNOSTICS,
            whereToFind = "Home -> IP Address / Gateway Info / Wi-Fi Tools",
            whatItDoes = "Inspects Android ConnectivityManager, LinkProperties, NetworkCapabilities, and Wi-Fi driver telemetry to cleanly separate physical interfaces from virtual VPN tunnels and show exact IPv4/IPv6, subnet mask, gateway, DNS, MTU, and optional Public IP details.",
            whenToUse = listOf(
                "When verifying DHCP lease accuracy (IPv4 address, prefix/subnet mask, gateway, DNS servers).",
                "When diagnosing VPN split/full-tunnel behavior, MTU fragmentation issues, or carrier IPv6/464XLAT translation.",
                "When checking connected Wi-Fi RSSI, link speed, frequency band, or available SNR telemetry."
            ),
            howToUseSteps = listOf(
                "Open IP Address, Gateway Info, or Wi-Fi Tools from the Home grid.",
                "Review Connection Information: Connection Type, Network Transport, VPN Status, Validated Status, and Active Interface.",
                "Scroll to IP Configuration to inspect IPv4/IPv6 addresses, Subnet Mask, Prefix Length, and MTU (shown as Unavailable if Android does not report >0).",
                "Review Default Gateway, DNS Servers, and Special-Use IPv4 warnings (RFC 1918, CGNAT 100.64/10, Link-Local 169.254/16, RFC 5737).",
                "Tap Public IP Address lookup when you need to confirm external NAT egress, or tap Copy/Save RTF for redacted documentation."
            ),
            understandingResults = listOf(
                "Physical vs Virtual Interface" to "Clearly separates physical radios/NICs (e.g., wlan0) from VPN virtual interfaces (e.g., tun0).",
                "Subnet Mask & Prefix Length" to "Defines local subnet boundaries (e.g., /24 = 255.255.255.0).",
                "MTU" to "Maximum Transmission Unit in bytes; displays Unavailable rather than a misleading '0' when unexposed.",
                "Wi-Fi Telemetry (when Wi-Fi active)" to "Shows RSSI (dBm), link speed, frequency/channel, and hardware Noise/SNR only if driver exposes them."
            ),
            whatResultsIndicate = listOf(
                "169.254.x.x IPv4 Address" to "DHCP failure—the device fell back to APIPA link-local because no DHCP server replied.",
                "VPN Active with Cellular underlying" to "Traffic leaves via encrypted tunnel over mobile data, not site Wi-Fi.",
                "100.64.x.x address" to "Carrier-Grade NAT (CGNAT / RFC 6598); inbound port forwarding will not work directly."
            ),
            troubleshootingScenarios = listOf(
                "Connected to Wi-Fi but local printer unreachable" to "Check IPv4 subnet and VPN status—either you are on Guest isolation, wrong VLAN subnet, or VPN hijacked local routes.",
                "SSID shows <unknown ssid>" to "Android OS requires Location/Nearby Wi-Fi permission and device Location Services ON; this is an OS privacy rule, not a broken Wi-Fi card."
            ),
            limitations = listOf(
                "Android restricts raw client hardware MAC addresses to randomized/placeholder values for non-system apps.",
                "Noise floor and SNR require kernel/driver exposure (/proc/net/wireless); unsupported devices honestly display Unavailable.",
                "Ethernet hardware link speed is often omitted by standard Android APIs."
            ),
            beginnerTip = "Check three numbers first: your IPv4 address (should not start with 169.254), your Default Gateway, and your DNS server.",
            intermediateGuidance = "Use Physical vs VPN separation and MTU readouts to spot encapsulation overhead or missing IPv4/IPv6 dual-stack routes.",
            advancedReference = "Inspects NetworkConnectionIdentifier classifications, LinkProperties.getRoutes()/getLinkAddresses()/getMtu(), RFC 1918/6598/5737 IPv4 scope checks, and Privacy-redacted RTF reports.",
            quickHelpBullets = listOf(
                "Distinguishes Connection Type, Transport, Interface name, and active VPN tunnel.",
                "IPv4, Subnet Mask, Prefix Length, IPv6, Gateway, DNS, and MTU come live from Android LinkProperties.",
                "Public IP lookup checks external NAT egress on demand.",
                "Unavailable metrics (MTU=0, unexposed noise floor) are never fabricated."
            ),
            relatedGlossaryIds = listOf("ip_address", "ipv4", "ipv6", "subnet_mask", "default_gateway", "dhcp", "vpn", "nat", "mac_address")
        ),

        // 3. Ping Test
        ToolGuideContent(
            id = "ping_test",
            title = "Ping Test (Latency & Loss)",
            subtitle = "Auto, ICMP & TCP Socket reachability probes with live D3 latency trends",
            category = ToolGuideCategory.CORE_DIAGNOSTICS,
            whereToFind = "Home -> Ping Test",
            whatItDoes = "Sends repeated timing probes to an IP address or hostname to measure packet loss, min/avg/max/mdev round-trip time (RTT), TTL behavior, and live jitter trends via an interactive D3 latency chart.",
            whenToUse = listOf(
                "To verify basic Layer-3 reachability to your Default Gateway (local check) vs 8.8.8.8 / 1.1.1.1 (internet check).",
                "To detect intermittent packet loss or latency spikes on unstable Wi-Fi or congested circuits.",
                "When ICMP is blocked by a carrier/firewall and you need Socket (TCP) reachability probing."
            ),
            howToUseSteps = listOf(
                "Enter an IP address or domain name, or tap a preset chip: 8.8.8.8, 1.1.1.1, google.com, or Gateway.",
                "Select Protocol Mode: Auto (Smart), ICMP Only, or Socket (TCP).",
                "Set Packet Count (or tap 4, 10, 20, or Cont. presets).",
                "Tap Start Ping to stream replies into the summary box, D3 latency chart, and packet log.",
                "Tap Stop Ping anytime to cancel cleanly without losing already-received statistics, or export/share redacted RTF reports."
            ),
            understandingResults = listOf(
                "Packets: X sent, Y received, Z% loss" to "Shows reliability; 0% loss is healthy.",
                "RTT min / avg / max / mdev" to "Lowest, mean, worst, and stability variation (mdev) in milliseconds.",
                "TTL in reply" to "Remaining hops reported by responder's initial TTL (64 Linux/macOS/routers, 128 Windows, 255 network gear).",
                "Timeout / Request timed out" to "No Echo Reply or TCP handshake arrived before deadline."
            ),
            whatResultsIndicate = listOf(
                "Low Gateway RTT (<5 ms) + high Internet RTT" to "Local Wi-Fi is fine; bottleneck is modem, ISP, or distant server.",
                "High Gateway RTT (>30 ms) or Gateway packet loss" to "Local Wi-Fi interference, weak RSSI, or overloaded local router.",
                "ICMP fails but Socket (TCP) works" to "Firewall or carrier drops ICMP ping packets while normal web ports still connect."
            ),
            troubleshootingScenarios = listOf(
                "Customers complain Zoom calls stutter despite fast Speed Test" to "Run 20-packet Ping toward Gateway and 1.1.1.1; watch max spikes and mdev jitter.",
                "Target domain says unable to resolve host" to "Switch to Ping 8.8.8.8—if IP ping succeeds while hostname ping fails, troubleshoot DNS next."
            ),
            limitations = listOf(
                "Many enterprise servers, cloud load balancers, and Android carrier gateways rate-limit or block ICMP Echo.",
                "Socket (TCP) mode measures TCP handshake delay to open service ports (e.g. 443/80) rather than raw kernel ICMP."
            ),
            beginnerTip = "Ping 'Gateway' first! If Gateway works (<10 ms, 0% loss), your Wi-Fi to the router is healthy; then ping 8.8.8.8 to test the internet.",
            intermediateGuidance = "Compare Auto/ICMP against Socket (TCP) when ICMP appears 100% blocked, and inspect mdev to spot jitter invisible in a single-packet average.",
            advancedReference = "CoroutinePingEngine records DNS lookup duration separately from ICMP/TCP socket probes, persists session summaries into Room Test History, and redacts Wi-Fi identifiers on RTF exports.",
            quickHelpBullets = listOf(
                "Auto (Smart) picks best available probe; ICMP Only uses echo probes; Socket (TCP) tests TCP handshake reachability.",
                "RTT (min/avg/max/mdev) tracks response speed and delay consistency.",
                "Packet Loss (%) counts unanswered probes; Timeouts happen when no reply arrives in time.",
                "Stop Ping cleanly halts running probes while preserving collected stats."
            ),
            relatedGlossaryIds = listOf("ping", "icmp", "packet", "packet_loss", "latency", "rtt", "jitter", "ttl", "timeout")
        ),

        // 4. DNS Lookup & Diagnostics
        ToolGuideContent(
            id = "dns_lookup",
            title = "DNS Lookup & Diagnostics",
            subtitle = "System & DoH query tool for A, AAAA, MX, TXT, NS, CNAME, PTR + Private DNS check",
            category = ToolGuideCategory.CORE_DIAGNOSTICS,
            whereToFind = "Home -> DNS Lookup",
            whatItDoes = "Inspects your device's active System DNS servers and Android Private DNS (DoT) state, and executes domain or reverse-IP queries across multiple record types using System Resolver and DNS-over-HTTPS (DoH).",
            whenToUse = listOf(
                "When websites fail to load by name even though IP pings (like 8.8.8.8) work.",
                "To compare what your local router/ISP DNS resolves versus public DoH (Google / Cloudflare).",
                "To verify A (IPv4), AAAA (IPv6), CNAME aliases, MX mail routing, NS authority, TXT SPF/verification, or PTR reverse records."
            ),
            howToUseSteps = listOf(
                "Inspect the top Active DNS Configuration card to view System DNS servers and Private DNS (DNS-over-TLS) mode.",
                "Type a hostname (e.g., example.com) or an IP address for reverse PTR lookup, or pick a quick preset.",
                "Choose record type chip (All, A, AAAA, CNAME, MX, TXT, NS, PTR).",
                "Tap Lookup / Run Query and inspect query timing (ms), resolved values, TTL, and System vs DoH results.",
                "Use Copy or Save RTF to document DNS answers cleanly."
            ),
            understandingResults = listOf(
                "A / AAAA Records" to "Direct IPv4 and IPv6 addresses returned for the hostname.",
                "CNAME / MX / TXT / NS / PTR" to "Canonical aliases, mail exchangers, policy/verification strings, nameservers, and reverse IP names.",
                "Private DNS Mode" to "Off, Opportunistic (Automatic), or Strict hostname DoT configured in Android Settings.",
                "Failure Categories" to "NXDOMAIN (domain doesn't exist), SERVFAIL (upstream broken), Timeout, or Network Offline."
            ),
            whatResultsIndicate = listOf(
                "DoH succeeds while System DNS fails" to "Your local router/ISP DNS server is broken, hijacked, or unreachable—not the internet itself.",
                "Both fail with NXDOMAIN" to "Typo in hostname or DNS record truly does not exist publicly.",
                "Strict Private DNS unreachable" to "Captive portal or firewall blocking TCP port 853 (DoT) breaks all lookups until Private DNS is Automatic/Off."
            ),
            troubleshootingScenarios = listOf(
                "Customer changed Android Private DNS manually and now hotel Wi-Fi breaks" to "Inspect Active DNS card; Private DNS Strict mode blocks local portal redirects over port 853.",
                "Split-horizon internal server resolves wrong IP" to "System local DNS should return internal RFC 1918 IP while public DoH returns public WAN IP."
            ),
            limitations = listOf(
                "DoH queries go over HTTPS port 443 to public resolvers, so strictly private LAN-only domains will only resolve via local System DNS.",
                "Query TTL reflects resolver cache remaining seconds, not authoritative zone minimum when cached."
            ),
            beginnerTip = "If websites won't open, check DNS Lookup: if google.com resolves cleanly in DoH but fails on System DNS, the router's DNS setting is the culprit.",
            intermediateGuidance = "Always compare System DNS answers against DoH and verify Android Private DNS mode when diagnosing portal or split-DNS faults.",
            advancedReference = "Combines Android LinkProperties DNS inspection (including isPrivateDnsActive / privateDnsServerName) with Java InetAddress system resolution and RFC 8484 JSON DoH queries.",
            quickHelpBullets = listOf(
                "A = IPv4 address, AAAA = IPv6 address, CNAME = domain alias, MX = mail servers, PTR = IP-to-name.",
                "Compares local System DNS against DNS-over-HTTPS (DoH) to isolate ISP/router DNS bugs.",
                "Shows Android Private DNS (DoT) state and categorizes NXDOMAIN vs SERVFAIL vs Timeout."
            ),
            relatedGlossaryIds = listOf("dns", "dns_resolution", "dns_record", "a_record", "aaaa_record", "cname_record", "mx_record", "ttl")
        ),

        // 5. Wi-Fi Scanner
        ToolGuideContent(
            id = "wifi_scanner",
            title = "Wi-Fi Scanner & Analyzer",
            subtitle = "2.4 / 5 / 6 GHz AP discovery, RSSI dBm, channels, widths, security & live graph",
            category = ToolGuideCategory.WIFI_RF,
            whereToFind = "Home -> Wi-Fi Scanner",
            whatItDoes = "Scans surrounding 802.11 access points and reports SSID, BSSID, RSSI (dBm), signal tier, band (2.4 / 5 / 6 GHz), channel, channel width, Wi-Fi standard (Wi-Fi 4/5/6/6E/7), security suite, and real-time fluctuation graphing.",
            whenToUse = listOf(
                "Before placing or reconfiguring routers/APs to see neighbor channel congestion.",
                "To verify which BSSID and band (2.4 vs 5 vs 6 GHz) a room actually sees strongly.",
                "To audit weak or insecure encryption (Open, WEP/TKIP, WPS) or hidden SSID beacons."
            ),
            howToUseSteps = listOf(
                "Grant Location and Nearby Wi-Fi permissions when prompted (and ensure device Location toggle is ON).",
                "Tap the Refresh/Scan icon in the top bar (or toggle Auto-Scan where permitted).",
                "Use Search plus Band chips (2.4 GHz / 5 GHz / 6 GHz) and Security/Sort menus to focus on the target SSID or cleanest channels.",
                "Expand any AP card or open the Congestion / Signal Graph view to watch live RSSI movement.",
                "Use the More Menu to save redacted RTF or privacy-redacted JSON reports (SSIDs/BSSIDs/MACs redacted before serialization)."
            ),
            understandingResults = listOf(
                "RSSI (dBm)" to "Values closer to 0 mean stronger signal (-45 dBm excellent, -67 dBm good threshold, -80 dBm weak/unreliable).",
                "Band & Channel" to "On 2.4 GHz, only channels 1, 6, and 11 at 20 MHz avoid partial-channel overlap.",
                "Channel Width" to "20 / 40 / 80 / 160 / 320 MHz; wider boosts peak PHY speed but raises interference risk.",
                "Security Badges" to "WPA3 (SAE), WPA2 (AES), Mixed, or legacy/Open security warnings."
            ),
            whatResultsIndicate = listOf(
                "Many strong APs on channel 3 or 8 (2.4 GHz)" to "Severe adjacent-channel interference; move site router to clearest of 1, 6, or 11 at 20 MHz.",
                "Same SSID with multiple BSSIDs" to "Mesh or multi-AP deployment; check RSSI balance across units to stop sticky clients."
            ),
            troubleshootingScenarios = listOf(
                "2.4 GHz is slow despite -48 dBm signal" to "Filter by 2.4 GHz and check Congestion—dozens of neighbor networks sharing overlapping channels eat airtime.",
                "Scan button shows throttle notice" to "Android limits foreground Wi-Fi scans (typically 4 scans per 2 minutes) unless Wi-Fi scan throttling is off in Developer Options."
            ),
            limitations = listOf(
                "Android 9+ enforces OS-level Wi-Fi scan throttling (up to ~4 scans per 2 minutes in foreground).",
                "Location Services + Location/Nearby Wi-Fi runtime permissions are mandatory under Android API rules for scan results.",
                "Reports apply mandatory privacy redaction to SSID, BSSID, and MAC fields on RTF, JSON, share, and clipboard exports."
            ),
            beginnerTip = "Remember: -50 dBm is MUCH stronger than -80 dBm! On 2.4 GHz, try to pick Channel 1, 6, or 11 with the fewest loud neighbors.",
            intermediateGuidance = "Check both RSSI and channel congestion/width—5 GHz 80 MHz beats a noisy 2.4 GHz 40 MHz link even at slightly lower dBm.",
            advancedReference = "Reads Android ScanResult metadata (centerFreq0/1, channelWidth, wifiStandard, capabilities); exports pass through TextReportExportManager redaction + verification prior to serialization.",
            quickHelpBullets = listOf(
                "RSSI (dBm) closer to 0 is stronger (-50 dBm = strong, -67 dBm = good voice cutoff, -82 dBm = weak).",
                "2.4 GHz reaches farther but crowds on 1/6/11; 5 GHz & 6 GHz run wider, cleaner channels with shorter wall reach.",
                "WPA3 / WPA2 indicate security locks; Android limits scans to ~4 per 2 minutes unless Developer throttling is off."
            ),
            relatedGlossaryIds = listOf("ssid", "bssid", "rssi", "dbm", "channel", "channel_width", "band_2_4_ghz", "band_5_ghz", "band_6_ghz", "interference", "wpa2", "wpa3")
        ),

        // 6. Signal Mapper
        ToolGuideContent(
            id = "signal_mapper",
            title = "Wi-Fi Signal Mapper",
            subtitle = "Room-by-room floor plan surveys, heatmap interpolation & AP placement tips",
            category = ToolGuideCategory.WIFI_RF,
            whereToFind = "Home -> Signal Mapper",
            whatItDoes = "Lets you model an apartment, house, office, or multi-floor layout, record live Wi-Fi RSSI/SNR sample pins per room, visualize an interpolated signal heatmap, detect dead zones, and generate AP placement suggestions.",
            whenToUse = listOf(
                "During pre-install or post-install Wi-Fi site surveys to prove coverage quality room-by-room.",
                "When diagnosing why back bedrooms, basements, or far offices drop off Wi-Fi.",
                "To generate visual dead-zone evidence and customer-ready survey reports."
            ),
            howToUseSteps = listOf(
                "Use the top More Menu -> New Custom Survey (or Configure Rooms & Areas) to set property template and room layout.",
                "Select the room you are physically standing in on the floor-plan canvas and confirm room selection.",
                "Wait a few seconds for live RSSI telemetry to settle, then record a Signal Sample pin in that spot.",
                "Walk room-to-room adding pins across corners and seating areas; toggle floors if surveying multi-story buildings.",
                "Inspect Coverage Analysis, Dead-Zone alerts, and AP Placement Suggestions, then Save/Share redacted RTF or JSON reports."
            ),
            understandingResults = listOf(
                "Color Heatmap & Sample Pins" to "Green/Teal (>= -65 dBm strong), Amber (-67 to -75 dBm marginal), Red (< -75 dBm weak / dead zone).",
                "Coverage % & Worst Room" to "Shows ratio of healthy vs weak survey samples across rooms.",
                "AP Placement Tips" to "Suggests central placement or secondary wired node locations based on weak-sample clustering."
            ),
            whatResultsIndicate = listOf(
                "Signal drops > 20 dB across one wall" to "Dense obstruction (concrete, tile, brick, metal foil insulation, HVAC ducting, or refrigerator).",
                "Entire upstairs floor < -72 dBm" to "Router in basement corner needs wired ceiling/wall AP or elevated central placement."
            ),
            troubleshootingScenarios = listOf(
                "Phone clings to distant AP while walking" to "Check BSSID in live telemetry—you may be measuring a sticky client holding onto the far router instead of nearest mesh node.",
                "Testing Signal Mapper workflow in office without walking a building" to "Tap the Demo Survey (sparkle) icon in the top bar to populate sample survey points."
            ),
            limitations = listOf(
                "Heatmap interpolates between your captured sample pins—sparse sampling reduces map accuracy.",
                "Demo Survey populates synthetic example points for demonstration and must not be confused with live RF measurements.",
                "Exported RTF/JSON/clipboard survey reports apply mandatory SSID/BSSID privacy redaction."
            ),
            beginnerTip = "Stand in 2-3 spots in each room (doorway, center, far corner) and take a sample pin—green rooms are great, red spots need router relocation or a mesh/AP node.",
            intermediateGuidance = "Pay attention to -67 dBm transitions across interior walls and confirm whether SNR is exposed or marked Unavailable by the handset.",
            advancedReference = "IDW spatial interpolation over configurable multi-floor room polygons persisted locally in SharedPreferences; exports redacted before JSON/RTF generation.",
            quickHelpBullets = listOf(
                "Map rooms/floors, tap rooms as you walk, and record live RSSI pins to build a visual heatmap.",
                "Color bands highlight Excellent/Good coverage vs Weak/Dead Zones (below -75 dBm).",
                "Auto-analyzes weak rooms and recommends AP/mesh placement; Demo Survey tests layout without live walk."
            ),
            relatedGlossaryIds = listOf("rssi", "dbm", "signal_strength", "snr", "noise", "access_point", "bssid")
        ),

        // 7. LAN Subnet Scanner
        ToolGuideContent(
            id = "lan_scanner",
            title = "LAN Subnet Scanner",
            subtitle = "Concurrent active host discovery & common service port checks on local subnets",
            category = ToolGuideCategory.PATH_THROUGHPUT,
            whereToFind = "Home -> LAN Scanner",
            whatItDoes = "Sweeps your active local Wi-Fi or Ethernet IPv4 subnet using concurrent probes to discover reachable hosts, identify the Default Gateway and your own device IP, measure host latency, and check common service ports.",
            whenToUse = listOf(
                "To verify whether a printer, camera, NVR, switch, server, or iPerf3 peer is online and discover its IP.",
                "To check whether client isolation (AP Isolation) is blocking device-to-device LAN communication.",
                "To confirm common open ports (HTTP 80, HTTPS 443, SSH 22, SMB 445, DNS 53, iPerf3 5201, etc.)."
            ),
            howToUseSteps = listOf(
                "Connect your device to the target local Wi-Fi or Ethernet network (disconnect full-tunnel VPN if scanning LAN).",
                "Check the detected Subnet Information card (local IP, CIDR subnet, usable sweep range).",
                "Tap Start Scan (or Refresh Subnet if you just switched networks) and monitor live discovery progress.",
                "Inspect responding devices, latency badges, Gateway/This Device tags, and discovered open ports.",
                "Tap Save RTF or Copy to record the discovered host list."
            ),
            understandingResults = listOf(
                "This Device / Gateway tags" to "Highlights your phone and the local router for orientation.",
                "Response Time (ms)" to "How fast the host answered TCP/reachability checks.",
                "Open Ports" to "Service ports responding to quick TCP connect handshakes (helpful for spotting web UIs, SSH, printers, or iPerf3 servers)."
            ),
            whatResultsIndicate = listOf(
                "Only Gateway + This Device found on busy Wi-Fi" to "Guest Wi-Fi AP/Client Isolation is likely blocking peer-to-peer LAN traffic.",
                "Printer responds on port 80/9100" to "Network path to printer is healthy; any print issue is driver/queue/app config."
            ),
            troubleshootingScenarios = listOf(
                "Subnet shows unavailable on cellular" to "LAN Scanner targets private local subnets on Wi-Fi or Ethernet—mobile carrier CGNAT links cannot be swept.",
                "Expected PC does not show up" to "Windows Defender Firewall blocks ICMP and idle ports silently; check if file sharing/HTTP/iPerf3 listener port is enabled."
            ),
            limitations = listOf(
                "Hosts with stealth firewalls dropping all TCP SYN probes and ICMP will not respond.",
                "Very large subnets (e.g. /16) are bounded safely so the phone doesn't flood or hang.",
                "Requires local Wi-Fi or Ethernet interface; disabled on cellular-only connections."
            ),
            beginnerTip = "Connect to the customer's Wi-Fi (not Guest mode), tap Start Scan, and look for the Router/Gateway plus your printers, cameras, or test computers.",
            intermediateGuidance = "If only the Gateway responds on a known busy LAN, suspect Guest Wi-Fi Client Isolation or an active VPN tunnel.",
            advancedReference = "Uses bounded coroutine concurrency across LinkProperties IPv4 subnet prefix (capped for oversized masks) with reachability + targeted common port TCP connect probes.",
            quickHelpBullets = listOf(
                "Sweeps local Wi-Fi/Ethernet IPv4 subnet to find online hosts, Gateway, and response latency.",
                "Detects common open ports (HTTP/HTTPS/SSH/SMB/iPerf3) to verify services.",
                "Stealth firewalls or Guest AP Client Isolation can hide devices even when connected."
            ),
            relatedGlossaryIds = listOf("lan", "ip_address", "subnet_mask", "cidr", "default_gateway", "tcp", "server")
        ),

        // 8. Traceroute & Topology Graph
        ToolGuideContent(
            id = "traceroute",
            title = "Traceroute & Path Topology",
            subtitle = "TTL-stepped router hop discovery with D3 interactive graph & IP scope tags",
            category = ToolGuideCategory.PATH_THROUGHPUT,
            whereToFind = "Home -> Traceroute",
            whatItDoes = "Traces the Layer-3 router path between your device and a target host by incrementally stepping IP TTL/Hop Limit, displaying per-hop IP addresses, hostnames, RTT latency, IP address scope badges (Private / CGNAT / Reserved / Public), and an interactive D3 hop topology graph.",
            whenToUse = listOf(
                "To see exactly where latency jumps or routing stalls between your local gateway, ISP core, and destination.",
                "To detect Carrier-Grade NAT (100.64.0.0/10), Private ISP hops, VPN detours, or routing loops.",
                "To compare D3 topological flow vs detailed per-hop text rows."
            ),
            howToUseSteps = listOf(
                "Enter a target IP or domain (e.g., 8.8.8.8 or google.com) or pick a preset, and set Max Hops (default 20).",
                "Tap Start Trace to watch hops appear in real time.",
                "Switch between Directed Graph (D3 topology) and List views using the toggle.",
                "Check scope tags on each hop (Local Gateway, Private RFC 1918, CGNAT, Reserved 240.0.0.0/4, Public) and RTT.",
                "Use Save RTF or Copy to export redacted trace documentation; traces also save to Diagnostic Test History."
            ),
            understandingResults = listOf(
                "Hop Number (TTL)" to "1 = first router (usually your gateway), 2 = ISP edge/ONT/modem or next router, increasing toward target.",
                "RTT (ms)" to "Round-trip time to that specific hop's control plane.",
                "* * * (Request Timed Out)" to "Router forwarded the packet silently without emitting an ICMP Time Exceeded reply.",
                "Scope Tags & Reserved Note" to "Marks Private, CGNAT (100.64/10), IPv6/464XLAT, or uncommon non-routable reserved replies (240.0.0.0/4)."
            ),
            whatResultsIndicate = listOf(
                "Hop 1 high RTT (>25 ms)" to "Problem starts immediately on Wi-Fi or local router.",
                "Single middle hop * * * followed by healthy target reply" to "Normal router control-plane ICMP suppression—NOT packet loss on your traffic!",
                "Hops stop permanently at Hop 2 with * * * onward" to "Likely WAN outage past the customer router or firewall blocking trace probes."
            ),
            troubleshootingScenarios = listOf(
                "On mobile data (T-Mobile/Verizon/etc.) Hop 1-2 looks unusual" to "Mobile carriers use 464XLAT / CGNAT and sometimes return Android sandboxed or reserved addresses—focus on whether the destination answers.",
                "Latency spikes at Hop 3 and stays high for every later hop" to "ISP regional handoff congestion starting at Hop 3."
            ),
            limitations = listOf(
                "Android app sandboxes do not allow raw root ICMP sockets; Traceroute uses system ping TTL probing and OS capabilities.",
                "Middle-hop timeouts (* * *) are common whenever core routers ignore TTL=0 replies; judge end-to-end health by the destination hop."
            ),
            beginnerTip = "Read Traceroute top-to-bottom: Hop 1 is your local router, Hop 2+ is your ISP. One '*' hop in the middle is normal as long as the final target responds.",
            intermediateGuidance = "A jump in latency that persists across all remaining hops marks the bottleneck boundary; a single high hop whose neighbors are fast is usually just busy router CPU.",
            advancedReference = "TracerouteEngine parses IPv4/IPv6 Ping/TTL probes, filters false/invalid hop syntax, flags Class E 240.0.0.0/4 and CGNAT 100.64/10 scopes, and visualizes directed flows in TracerouteD3GraphView.",
            quickHelpBullets = listOf(
                "Hop = each router step; TTL forces each router along the path to identify itself.",
                "Single '* * *' timeouts in the middle are normal when routers silence ICMP replies.",
                "Watch where latency jumps AND stays elevated to locate the real bottleneck."
            ),
            relatedGlossaryIds = listOf("traceroute", "hop", "ttl", "icmp", "rtt", "timeout", "nat", "private_ip", "public_ip")
        ),

        // 9. Internet Speed Test
        ToolGuideContent(
            id = "speed_test",
            title = "Internet Speed Test",
            subtitle = "Native streaming Download, Upload, Latency & Jitter + Web Speed Test launcher",
            category = ToolGuideCategory.PATH_THROUGHPUT,
            whereToFind = "Home -> Speed Test",
            whatItDoes = "Measures real-world internet throughput (Download Mbps, Upload Mbps, Peak vs Average), latency (min/avg/max ms), and RFC 3550 jitter directly via OkHttp multi-stream CDN endpoints, and also provides a launcher for standard web benchmarks (Speedtest.net, Cloudflare Speed, Fast.com, LibreSpeed, or a Custom URL).",
            whenToUse = listOf(
                "To verify ISP internet download/upload speeds against the customer's subscribed plan.",
                "To check loaded latency and jitter before diagnosing streaming, VoIP, or cloud-backup complaints.",
                "To compare cellular vs Wi-Fi WAN performance—or launch a web test site when a third-party benchmark is requested."
            ),
            howToUseSteps = listOf(
                "Check the top Connectivity Monitor header so you know whether you are testing Wi-Fi, Cellular, or VPN.",
                "Tap Start Speed Test to run Latency/Jitter probing, then Download, then Upload while watching the live gauge and progress bar.",
                "Review Download Mbps (Average & Peak), Upload Mbps (Average & Peak), Latency, Jitter tier, and transferred bytes.",
                "Optionally tap the Globe icon (or Change Service button) to open Speedtest.net, Cloudflare Speed, Fast.com, LibreSpeed, or a custom URL in WebTestActivity.",
                "Tap Copy or Save/Share RTF for a clean privacy-redacted speed report."
            ),
            understandingResults = listOf(
                "Average vs Peak Mbps" to "Average reflects sustained steady throughput across the full phase; Peak shows best 150 ms window burst.",
                "Ping / Latency (ms)" to "Baseline round-trip delay to the test CDN edge.",
                "Jitter (ms)" to "Consecutive probe RTT variance (RFC 3550): <5 ms Stable, 5-15 ms Moderate, >15 ms Variable.",
                "Mbps vs MB/s" to "Measured in Megabits per second (1 MB/s file download ~= 8 Mbps)."
            ),
            whatResultsIndicate = listOf(
                "Download fast but Upload < 5 Mbps" to "Expected on asymmetric cable/DSL/4G, OR indicates upstream RF noise / upload buffer issue if much lower than plan.",
                "Speeds fast near router, slow in bedroom" to "Wi-Fi RF attenuation bottleneck, not an ISP outage—confirm with Wi-Fi Scanner & Signal Mapper.",
                "Peak much higher than Average" to "Bursting/throttling, competing downloads on the network, or unstable Wi-Fi interference."
            ),
            troubleshootingScenarios = listOf(
                "Phone achieves 300 Mbps while laptop hits 90 Mbps" to "Check phone Wi-Fi link speed vs laptop Ethernet (100 Mbps bad cable/Fast Ethernet port) or older Wi-Fi card.",
                "Speed Test fails immediately or refuses custom URL" to "Verify internet access in top connectivity card; custom external URLs require https:// (cleartext http:// restricted strictly to local private diagnostics)."
            ),
            limitations = listOf(
                "Internet Speed Test reflects the slowest bottleneck anywhere between your phone, Wi-Fi, router, ISP, and CDN edge.",
                "Running through an active VPN caps speed at VPN encryption/server capacity.",
                "Multi-gigabit plans (>1 Gbps) exceed many mobile Wi-Fi radios and CDN single-client limits."
            ),
            beginnerTip = "Download is coming to your phone (video/web); Upload is going out (video calls/backups). Stand near the router first to check true ISP speed, then walk away to test Wi-Fi loss.",
            intermediateGuidance = "Compare Average vs Peak throughput and check Jitter status—real-time voice/video fails from high jitter long before Download Mbps looks 'low'.",
            advancedReference = "Uses SpeedTestEngine over OkHttp HTTP/HTTPS CDN payload streams with warmup exclusion, multi-stream aggregation, and RFC 3550 jitter calculation, plus validated WebView launcher.",
            quickHelpBullets = listOf(
                "Measures real HTTP Download/Upload Mbps (Average & Peak), Latency, and RFC 3550 Jitter.",
                "Mbps measures Megabits/sec (~8 Mbps = 1 MB/s); Peak bursts higher than sustained Average.",
                "Includes Web Launcher presets (Speedtest.net, Cloudflare, Fast.com, LibreSpeed, or Custom HTTPS/LAN URL)."
            ),
            relatedGlossaryIds = listOf("mbps", "gbps", "throughput", "bandwidth", "download", "upload", "peak_throughput", "average_throughput", "latency", "jitter")
        ),

        // 10. iPerf3 Client Mode
        ToolGuideContent(
            id = "iperf3_client",
            title = "iPerf3 Client Mode",
            subtitle = "Active TCP bandwidth testing (Upload & Download -R) to any iPerf3 server",
            category = ToolGuideCategory.PATH_THROUGHPUT,
            whereToFind = "Home -> iPerf3 Client / Server -> Client Mode",
            whatItDoes = "Acts as an active iPerf3-compatible TCP benchmark client connecting to an iPerf3 server on port 5201 (or custom port) to isolate pure local Wi-Fi/LAN or routed WAN TCP throughput without internet CDN variables.",
            whenToUse = listOf(
                "To measure true Wi-Fi-to-LAN speed to a local laptop, NAS, Raspberry Pi, or second phone running iPerf3 Server.",
                "To prove the local Wi-Fi delivers full speed (e.g., 600 Mbps internal) when a customer blames Wi-Fi for a slow ISP circuit.",
                "To inspect 1-second interval stability, TCP stalls, or asymmetric Upload vs Reverse Download (-R) throughput."
            ),
            howToUseSteps = listOf(
                "Ensure an iPerf3 v3 server is listening on the target machine (e.g. `iperf3 -s` on a wired PC, or another phone using this app's Server tab).",
                "In Client Mode, enter Server Host / IP (or tap Detect Gateway / local server IP) and Port (default 5201).",
                "Configure Duration (1-300s), Connect Timeout, Parallel Streams (1-8; try 4 for high-speed links), and Reverse Mode (-R) ON for Download or OFF for Upload.",
                "Tap Start Benchmark and watch live 1-second Interval Throughput updates.",
                "Review Total MB Transferred, Average Bandwidth, Peak Bandwidth, and diagnostics; Copy/Save redacted RTF if needed."
            ),
            understandingResults = listOf(
                "Upload (Default) vs Download (-R Reverse)" to "Upload sends bytes from phone -> server; Reverse (-R) pulls bytes from server -> phone.",
                "1-Second Interval Throughput" to "Shows whether speed held steady every second or collapsed/stalled mid-test.",
                "Total Transferred + Average / Peak Mbps" to "Exact TCP payload bytes delivered over actual active transfer time.",
                "Stall / Zero-Interval Diagnostic" to "Indicates firewall blocking data sockets, carrier upload shaping, or Path MTU blackhole."
            ),
            whatResultsIndicate = listOf(
                "Fast -R Download (500 Mbps) + Slow Upload (<5 Mbps or stalled)" to "Asymmetric link, RF hidden-node uplink contention, or cellular/VPN TCP upload shaping/PMTUD drop.",
                "Port 5201 connects on control channel, then 0 Mbps transfer" to "Firewall allows port 5201 control but blocks subsequent data stream or server closed unexpectedly."
            ),
            troubleshootingScenarios = listOf(
                "Upload works for 1 second then drops to 0.0 Mbps" to "Inspect VPN/MTU or cellular firewall; bounded 64 KB upload socket buffering prevents fake buffered bursts and exposes true wire stall.",
                "Single stream tops out around 150 Mbps on Wi-Fi 6" to "Increase Parallel Streams to 4 so multiple TCP congestion windows fill the high-BDP wireless pipe."
            ),
            limitations = listOf(
                "Built-in iPerf3 engine implements TCP benchmarking only (UDP jitter/loss mode is not part of this built-in engine).",
                "Testing across the internet requires port 5201 TCP open on the destination router/firewall."
            ),
            beginnerTip = "Put a wired laptop on the router running `iperf3 -s`, enter that laptop's local IP here, and run a 10-second test to see your true Wi-Fi speed without internet limits.",
            intermediateGuidance = "Use 4 Parallel Streams on gigabit/Wi-Fi 6 networks and run both Upload (Reverse OFF) and Download (Reverse ON) to check symmetry.",
            advancedReference = "ESnet iPerf3 TCP JSON control-channel handshake + concurrent Socket streams with SO_SNDBUF=64KB / SO_RCVBUF=256KB pacing, interval watchdog, and zero-interval diagnostic warnings.",
            quickHelpBullets = listOf(
                "Client initiates TCP streams to an iPerf3 server (default port 5201) to measure pure LAN/WAN throughput.",
                "Reverse Mode (-R) OFF tests Upload (Device -> Server); ON tests Download (Server -> Device).",
                "Parallel Streams (1-8) help saturate fast links; built-in engine supports TCP mode cleanly."
            ),
            relatedGlossaryIds = listOf("iperf3", "tcp", "udp", "client", "server", "parallel_streams", "buffer", "upload", "download", "throughput")
        ),

        // 11. iPerf3 Server Mode
        ToolGuideContent(
            id = "iperf3_server",
            title = "iPerf3 Server Mode",
            subtitle = "On-device TCP iPerf3 listener on port 5201+ for peer-to-peer LAN benchmarks",
            category = ToolGuideCategory.PATH_THROUGHPUT,
            whereToFind = "Home -> iPerf3 Client / Server -> Server Mode",
            whatItDoes = "Turns your Android device into a listening iPerf3 v3 TCP responder (default port 5201) so another device—such as a laptop running `iperf3 -c <phone-ip>` or another phone with this toolkit—can benchmark speeds directly against your phone.",
            whenToUse = listOf(
                "When walking around a site with your phone while a colleague or test laptop initiates tests toward the phone's IP.",
                "When benchmarking device-to-device Wi-Fi throughput between two mobile devices."
            ),
            howToUseSteps = listOf(
                "Switch to the Server tab in iPerf3 Benchmark and note your displayed local IP address.",
                "Verify Listening Port (default 5201) and ensure both devices are on the same reachable network without Guest isolation.",
                "Tap Start Server, then point the remote iPerf3 client at your phone's IP and port.",
                "Keep the iPerf3 screen open in the foreground while testing; watch live interval speed on both devices.",
                "Tap Stop Server when done (or rely on the built-in 2-minute idle auto-stop safety timeout)."
            ),
            understandingResults = listOf(
                "Listening IP & Port" to "The exact IP:Port the remote client must dial.",
                "Client Connected & Direction" to "Shows peer IP and whether peer is sending (Client Upload) or pulling (-R Client Download).",
                "Measured Stream Bandwidth" to "1-second and final sustained TCP throughput received or transmitted."
            ),
            whatResultsIndicate = listOf(
                "Remote client gets Connection Refused" to "Server isn't started, wrong port, or wrong IP entered on client.",
                "Remote client times out silently" to "AP Client Isolation, guest VLAN firewall, or cellular carrier NAT blocking inbound TCP 5201."
            ),
            troubleshootingScenarios = listOf(
                "Server stops after switching to another app" to "By design, Server Mode automatically stops when you leave the screen/app or after 2 minutes idle to protect battery and avoid background sockets.",
                "Cellular WAN test to phone server fails" to "Mobile carriers block unsolicited inbound ports via CGNAT/firewall; use iPerf3 Server on reachable LAN/VPN topologies."
            ),
            limitations = listOf(
                "TCP iPerf3 sessions only (no UDP server mode).",
                "Automatically stops if idle for 2 minutes or if the screen is exited/backgrounded (no persistent background foreground-service daemon)."
            ),
            beginnerTip = "Open Server Mode, read your phone's IP address on screen, tap Start Server, and keep this screen open while your other device runs the Client test.",
            intermediateGuidance = "Supports both normal client upload and `-R` reverse download requests over standard ESnet iPerf3 v3 control framing.",
            advancedReference = "Binds a foreground ServerSocket listener on port 5201+ with BackHandler cleanup, 120s idle timeout watchdog, and compatible JSON state transitions.",
            quickHelpBullets = listOf(
                "Listens on TCP port 5201 (configurable) so laptops or peer phones can run iPerf3 tests against this phone.",
                "Keep app screen open while running; automatically stops after 2 minutes idle or when leaving.",
                "Cellular CGNAT and Guest Wi-Fi isolation block inbound client connections—best used on internal LAN/Wi-Fi."
            ),
            relatedGlossaryIds = listOf("server", "client", "iperf3", "tcp", "buffer", "nat", "lan")
        ),

        // 12. Subnet Calculator
        ToolGuideContent(
            id = "subnet_calculator",
            title = "IPv4 CIDR Subnet Calculator",
            subtitle = "Compute Network, Broadcast, Mask, Wildcard, Host Range, Count & Binary Octets",
            category = ToolGuideCategory.UTILITIES_RECORDS,
            whereToFind = "Home -> Subnet Calculator",
            whatItDoes = "Takes any IPv4 address and CIDR prefix (/0 to /32) and instantly calculates Network Address, Broadcast Address, Subnet Mask, Wildcard Mask, First/Last Usable Host IP, Total vs Usable Host counts, IP Class, RFC Scope, and Binary octet breakdown.",
            whenToUse = listOf(
                "When configuring static IPs, DHCP scopes, router LAN subnets, or ACL wildcard masks.",
                "When checking whether two IPs sit inside the same subnet or need routing through a gateway.",
                "For quick field calculation of /24, /25, /26, /30 point-to-point, or /31 RFC 3021 links."
            ),
            howToUseSteps = listOf(
                "Enter an IPv4 address (or pick a quick preset like 192.168.1.0/24 or 10.0.0.0/8).",
                "Drag the CIDR slider or pick prefix length from /0 to /32.",
                "Review Network Address, Broadcast Address, Subnet Mask, Wildcard Mask, and Usable Host Range.",
                "Scroll down to inspect Binary Octet visualization and RFC scope classification (RFC 1918, CGNAT, Public, etc.).",
                "Copy or Save RTF report when documenting site addressing plans."
            ),
            understandingResults = listOf(
                "Network & Broadcast Address" to "First and last addresses reserved for the subnet itself (not assigned to normal devices in /0../30).",
                "Subnet vs Wildcard Mask" to "Subnet Mask marks network bits with 1s (255.255.255.0); Wildcard inverts it (0.0.0.255) for router ACLs/OSPF.",
                "Usable Host Count" to "2^(32 - prefix) - 2 for standard subnets (/31 point-to-point has 2 RFC 3021 hosts; /32 is 1 host route)."
            ),
            whatResultsIndicate = listOf(
                "Device IP falls outside expected First..Last Usable range" to "Wrong subnet mask or wrong VLAN port/SSID.",
                "Overlap between Guest and Corporate ranges" to "Adjust prefix length or base network block to prevent routing conflicts."
            ),
            troubleshootingScenarios = listOf(
                "Need exactly 50 devices on a small VLAN" to "Slide CIDR to /26 (255.255.255.192) -> 62 usable hosts, avoiding waste while leaving room.",
                "Point-to-point router link only needs 2 IPs" to "Use /30 (2 usable hosts) or /31 (2 RFC 3021 point-to-point hosts)."
            ),
            limitations = listOf(
                "Calculator targets IPv4 CIDR addressing (IPv6 prefix calculator is not included in this screen)."
            ),
            beginnerTip = "In a common /24 network (mask 255.255.255.0), the first 3 numbers identify the network and the last number (.1 to .254) goes to your 254 devices.",
            intermediateGuidance = "Watch how moving the CIDR slider by +1 cuts usable hosts in half while doubling the number of subnets inside a parent block.",
            advancedReference = "Supports full /0-/32 bitmath including /31 RFC 3021 and /32 host-route edge cases, wildcard bit inversion, and RFC scope detection.",
            quickHelpBullets = listOf(
                "Enter any IPv4 + CIDR slider (/0-/32) to inspect Network, Broadcast, Mask, Wildcard, and Usable Range.",
                "/24 = 255.255.255.0 (254 usable hosts); each +1 CIDR halves host capacity.",
                "Binary octets show exactly where the network boundary cuts through the 32 bits."
            ),
            relatedGlossaryIds = listOf("ipv4", "subnet_mask", "cidr", "private_ip", "lan", "ip_address")
        ),

        // 13. Network Notes
        ToolGuideContent(
            id = "network_notes",
            title = "Network Notes (Site Documentation)",
            subtitle = "Local Room database for customer notes, categories, IP tags & pinned reminders",
            category = ToolGuideCategory.UTILITIES_RECORDS,
            whereToFind = "Home -> Network Notes",
            whatItDoes = "Stores persistent on-device field technician notes in a local Room database with title, category, optional IP/host tag, body details, pin-to-top priority, search/category filters, and redacted RTF export/share.",
            whenToUse = listOf(
                "To log rack switch ports, ISP circuit IDs, gateway IPs, AP locations, or follow-up punch-list items onsite.",
                "To pin critical warning notes to the top during multi-hour cutovers or troubleshooting.",
                "To export clean site handoff notes via SAF RTF or Share."
            ),
            howToUseSteps = listOf(
                "Tap the + floating button to create a new Network Note.",
                "Fill in Title, Category (for example Wi-Fi, Routing, Hardware, Outage), optional IP/Host tag, and detailed Note content.",
                "Tap the Pin icon on urgent notes to keep them fixed at the top, or use the Search and Category filter bar.",
                "Tap Edit or Delete on individual cards to maintain clean site logs.",
                "Use Save RTF or Share icons in the top bar (notes pass through privacy redaction for any accidental SSID/BSSID/MAC strings)."
            ),
            understandingResults = listOf(
                "Pinned Notes" to "Appear above standard timestamp-ordered notes for fast access.",
                "Category & IP Tags" to "Make filtering across dozens of field tickets quick."
            ),
            whatResultsIndicate = listOf(
                "Structured IP/Host tags + timestamps" to "Provide chronological audit trail when escalating tickets to Tier-2 or ISP NOC."
            ),
            troubleshootingScenarios = listOf(
                "Exported notes look masked around Wi-Fi MACs" to "Intentional security redaction protects customer SSID/BSSID/MAC identifiers before leaving the device."
            ),
            limitations = listOf(
                "Stored strictly locally in device Room database (no cloud sync across devices).",
                "App exports intentionally redact sensitive Wi-Fi SSID/BSSID/MAC patterns."
            ),
            beginnerTip = "Avoid writing sensitive passwords in notes; log device IPs, switch ports, rooms, and fault symptoms so you remember every detail on return visits.",
            intermediateGuidance = "Pair Network Notes with Test History exports so quantitative Ping/Traceroute proof accompanies written field observations.",
            advancedReference = "Room `network_notes` entity with live Flow sorting (`isPinned DESC, timestamp DESC`), search filtering, and TextReportExportManager SAF/FileProvider RTF pipeline.",
            quickHelpBullets = listOf(
                "Save offline Room notes with Category, IP/Host tags, and Pin-to-top support.",
                "Search and filter by category anytime; export/share redacted RTF summaries.",
                "Stored strictly locally on-device—never uploaded or synced externally."
            ),
            relatedGlossaryIds = listOf("ip_address", "mac_address", "ssid", "bssid")
        ),

        // 14. Diagnostic Test History
        ToolGuideContent(
            id = "test_history",
            title = "Diagnostic Test History",
            subtitle = "Saved Ping & Traceroute session logs with Search, CSV, RTF & JSON exports",
            category = ToolGuideCategory.UTILITIES_RECORDS,
            whereToFind = "Home -> Test History (or 'Saved History' inside Ping Test)",
            whatItDoes = "Lists automatically saved Ping and Traceroute runs from the local Room database, allowing you to search by host/date, compare before/after fixes, delete stale sessions, or export CSV, RTF, and privacy-redacted JSON reports.",
            whenToUse = listOf(
                "To compare baseline latency/packet loss BEFORE vs AFTER moving an AP, swapping a cable, or turning off VPN.",
                "To attach structured CSV, RTF, or JSON evidence to a field service ticket.",
                "To inspect past Traceroute hop lists without re-running the trace."
            ),
            howToUseSteps = listOf(
                "Switch between Ping Tests and Traceroute tabs at the top.",
                "Use the Search box to filter by target hostname/IP.",
                "Expand any session card to view full metrics (sent/received/loss%, min/avg/max/mdev, hop table) or delete single entries.",
                "Tap the Share/Export icon or More Menu to export CSV (Ping data), RTF, or privacy-redacted JSON reports via SAF or Share."
            ),
            understandingResults = listOf(
                "Ping History Card" to "Tracks Target, Timestamp, Protocol mode, Packet Loss %, and Min/Avg/Max RTT.",
                "Traceroute History Card" to "Tracks Target, Hop count, Total Trace time, and expandable hop-by-hop RTT list."
            ),
            whatResultsIndicate = listOf(
                "Loss drops from 12% to 0% after AP move" to "Quantifiable field proof that relocation resolved wireless drops."
            ),
            troubleshootingScenarios = listOf(
                "Need raw spreadsheet metrics for SLA reporting" to "Choose Export Ping CSV to Storage / Downloads for tabular import into Excel or Google Sheets."
            ),
            limitations = listOf(
                "History records Ping and Traceroute runs locally; clearing app storage or tapping Clear History deletes stored records permanently.",
                "All exported TXT/RTF/JSON reports redact detected Wi-Fi identifiers prior to sharing."
            ),
            beginnerTip = "Always run a Ping or Traceroute BEFORE changing anything, then run it again AFTER your fix—Test History saves both automatically so you can compare.",
            intermediateGuidance = "Use Search + CSV export for trend comparisons, and redacted JSON/RTF exports when attaching diagnostics to customer tickets.",
            advancedReference = "Room `ping_results` + `traceroute_results` tables via `DiagnosticHistoryRepository`, exported through `DiagnosticReportExporter` and `TextReportExportManager` redaction checks.",
            quickHelpBullets = listOf(
                "Automatically saves completed Ping and Traceroute runs locally in Room.",
                "Compare before-and-after tests across Ping & Traceroute tabs.",
                "Exports CSV spreadsheets, RTF documents, and privacy-redacted JSON reports."
            ),
            relatedGlossaryIds = listOf("ping", "traceroute", "packet_loss", "rtt", "latency")
        ),

        // 15. Reports & Privacy Redaction
        ToolGuideContent(
            id = "reports_privacy",
            title = "Report Exports & Privacy Redaction",
            subtitle = "How RTF, JSON, CSV, Share & Clipboard exports protect customer Wi-Fi privacy",
            category = ToolGuideCategory.UTILITIES_RECORDS,
            whereToFind = "Top bar / More Menu / Export actions across diagnostic screens",
            whatItDoes = "Applies automated privacy redaction before writing RTF, JSON, Share attachments, or sensitive Clipboard copies—stripping SSIDs, BSSIDs, and MAC addresses and verifying the final serialized output so customer wireless identifiers do not leak.",
            whenToUse = listOf(
                "Whenever saving field test documentation to device Downloads/Storage (via Android SAF) or sharing reports to email/ticketing systems.",
                "When copying Wi-Fi scans, network info, or ping results to clipboard safely."
            ),
            howToUseSteps = listOf(
                "Tap Save RTF (Save Report) to pick a safe user-chosen folder via Android Storage Access Framework (SAF).",
                "Tap Share Report (RTF or JSON) to pass a temporary FileProvider URI to email or messaging apps.",
                "Tap Copy Report—on Android 13+, sensitive copies flag ClipDescription.EXTRA_IS_SENSITIVE to hide content from keyboard preview popups."
            ),
            understandingResults = listOf(
                "[REDACTED_SSID] / [REDACTED_BSSID] / [REDACTED_MAC]" to "Markers replacing discovered wireless network names and hardware MAC/BSSID addresses.",
                "SAF Confirmation Toast" to "Confirms exact saved filename and byte count after redaction verification."
            ),
            whatResultsIndicate = listOf(
                "Redaction verification check" to "Ensures no unredacted connected SSID/BSSID leaked into nested JSON fields or RTF paragraphs before export completes."
            ),
            troubleshootingScenarios = listOf(
                "Export fails with cancellation message" to "If you dismiss the Android folder picker without pressing Save, no partial empty file is written."
            ),
            limitations = listOf(
                "Privacy redaction targets wireless identifiers (SSID, BSSID, MAC) and sensitive tokens; technical IPs/hostnames tested (e.g., 8.8.8.8, public/RFC1918 targets) remain intact so diagnostic reports stay useful."
            ),
            beginnerTip = "You can safely share or save reports from Wi-Fi Scanner, Network Info, and Signal Mapper—customer Wi-Fi names and MAC addresses are automatically scrubbed out.",
            intermediateGuidance = "Always verify whether you need RTF (human-readable formatted report), JSON (structured automation/audit), or CSV (Ping History spreadsheet).",
            advancedReference = "Enforced by TextReportExportManager (pre-serialization redaction, post-serialization verifyPrivacyRedaction gate, SAF staging, FileProvider share, and Android 13+ EXTRA_IS_SENSITIVE clipboard metadata).",
            quickHelpBullets = listOf(
                "Automatically scrubs SSIDs, BSSIDs, and MAC addresses across RTF, JSON, Share, and Clipboard outputs.",
                "Android 13+ copies mark ClipDescription.EXTRA_IS_SENSITIVE for keyboard privacy.",
                "Uses Android SAF folder picker + FileProvider URIs without broad external storage permissions."
            ),
            relatedGlossaryIds = listOf("ssid", "bssid", "mac_address")
        )
    )

    val troubleshootingPlaybook: List<TroubleshootingPlaybookItem> = listOf(
        TroubleshootingPlaybookItem(
            id = "pb_no_internet",
            symptom = "Connected to Wi-Fi, But No Internet",
            recommendedToolIds = listOf("live_connectivity", "network_info", "ping_test", "dns_lookup"),
            stepByStepChecks = listOf(
                "1. Check Home LIVE CONNECTIVITY card: distinguish 'LIMITED / CAPTIVE PORTAL' (needs splash login) vs 'NO INTERNET ACCESS'.",
                "2. Open Connection Diagnostics: check if IPv4 starts with 169.254.x.x (DHCP failed) or if VPN is active.",
                "3. Open Ping Test -> tap 'Gateway' preset: <10 ms & 0% loss proves local Wi-Fi link to router is healthy.",
                "4. Ping 8.8.8.8 directly: if IP ping succeeds while websites fail, run DNS Lookup to verify if System DNS failed while DoH works."
            ),
            rookiePitfall = "Replacing cables or blaming the modem before checking whether Private DNS Strict Mode or a broken local DHCP DNS server is the only fault."
        ),
        TroubleshootingPlaybookItem(
            id = "pb_works_near_router_only",
            symptom = "Wi-Fi Fast Near Router, Drops or Stalls in Far Rooms",
            recommendedToolIds = listOf("wifi_scanner", "signal_mapper", "ping_test"),
            stepByStepChecks = listOf(
                "1. Open Wi-Fi Scanner near the router vs inside the problem room: compare RSSI (dBm) and Band (5 GHz/6 GHz vs 2.4 GHz).",
                "2. Walk with Signal Mapper taking 2-3 sample pins per room to confirm dead zones (below -75 dBm) across heavy walls.",
                "3. Run a 20-packet Ping toward Gateway in the problem room to verify wireless jitter/packet loss."
            ),
            rookiePitfall = "Placing a mesh extender inside the dead zone itself—always place repeaters/nodes midway where they still receive at least -65 dBm (or wire via Ethernet backhaul)."
        ),
        TroubleshootingPlaybookItem(
            id = "pb_good_bars_slow",
            symptom = "Strong Wi-Fi Signal (-50 dBm), But Sluggish Speeds",
            recommendedToolIds = listOf("wifi_scanner", "speed_test", "iperf3_client"),
            stepByStepChecks = listOf(
                "1. In Wi-Fi Scanner, check whether you landed on crowded 2.4 GHz instead of 5 GHz, or share an overlapping channel (not 1/6/11).",
                "2. Run iPerf3 Client to a wired local server: fast LAN speed (>300 Mbps) + slow Internet Speed Test proves an ISP/WAN bottleneck, not Wi-Fi.",
                "3. If iPerf3 local speed is also slow despite -50 dBm, test moving AP off congested channels or narrowing 40 MHz 2.4 GHz back to 20 MHz."
            ),
            rookiePitfall = "Assuming 'full signal bars' means low interference—adjacent-channel noise on 2.4 GHz still causes constant frame retries."
        ),
        TroubleshootingPlaybookItem(
            id = "pb_ip_works_dns_fails",
            symptom = "Can Ping 8.8.8.8, Cannot Open Websites",
            recommendedToolIds = listOf("dns_lookup", "network_info", "ping_test"),
            stepByStepChecks = listOf(
                "1. Open DNS Lookup -> inspect Active DNS Configuration card for System DNS servers and Private DNS (DoT) status.",
                "2. Query google.com across System vs DoH: if DoH succeeds and System times out/SERVFAILs, fix router DHCP DNS or toggle Private DNS.",
                "3. Remember captive portals hijack DNS until signed in—verify Home card isn't showing Captive Portal."
            ),
            rookiePitfall = "Leaving Android Private DNS set to an unreachable custom/corporate DoT hostname while testing public hotspots."
        ),
        TroubleshootingPlaybookItem(
            id = "pb_vpn_breaks_lan",
            symptom = "Cannot Reach Printers, NAS, or Local iPerf3 / LAN Devices",
            recommendedToolIds = listOf("network_info", "live_connectivity", "lan_scanner"),
            stepByStepChecks = listOf(
                "1. Check VPN Status on Home or Network Info: an active virtual tunnel (tun0) often intercepts RFC 1918 local routes.",
                "2. Verify IPv4 subnet on Network Info matches expected local site VLAN rather than Guest Wi-Fi isolation.",
                "3. Pause full-tunnel VPN or switch off Guest SSID, then re-run LAN Scanner."
            ),
            rookiePitfall = "Scanning 192.168.1.0/24 while a corporate VPN routes every packet—including local LAN targets—offsite."
        ),
        TroubleshootingPlaybookItem(
            id = "pb_slow_upload_stalls",
            symptom = "Good Download Speed, Slow or Stalled Upload / Video Calls",
            recommendedToolIds = listOf("speed_test", "iperf3_client", "traceroute", "ping_test"),
            stepByStepChecks = listOf(
                "1. Inspect Speed Test Average vs Peak Upload Mbps and Jitter tier.",
                "2. Compare iPerf3 Reverse (-R Download) vs standard Client Upload to a local wired server: isolates Wi-Fi uplink contention from ISP shaping.",
                "3. Inspect MTU on Network Info and run Traceroute to check for VPN overhead, CGNAT hops, or asymmetric path loss."
            ),
            rookiePitfall = "Judging link health only by Download Mbps; residential cable/cellular links often have tiny upload pipes that saturate during cloud backups."
        )
    )

    val advancedReferences: List<AdvancedReferenceItem> = listOf(
        AdvancedReferenceItem(
            id = "ref_ipv4_scopes",
            title = "IPv4 Special-Use & Field Recognition Reference",
            subtitle = "Critical CIDR blocks surfaced by Network Info, Subnet Calculator & Traceroute",
            bulletPoints = listOf(
                "RFC 1918 Private LAN: 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16 — local sites/enterprise VLANs.",
                "RFC 6598 Carrier-Grade NAT (CGNAT): 100.64.0.0/10 — ISP shared NAT; breaks unsolicited inbound port forwarding.",
                "RFC 3927 APIPA / Link-Local: 169.254.0.0/16 — indicates failed DHCP lease when assigned on client Wi-Fi/Ethernet.",
                "RFC 5737 Documentation Ranges: 192.0.2.0/24 (TEST-NET-1), 198.51.100.0/24 (TEST-NET-2), 203.0.113.0/24 (TEST-NET-3).",
                "RFC 1112 Reserved Class E: 240.0.0.0/4 — sometimes seen in virtualized/carrier translation quirks; flagged explicitly rather than treated as public internet."
            )
        ),
        AdvancedReferenceItem(
            id = "ref_wifi_thresholds",
            title = "IEEE 802.11 RSSI, SNR & Channel Math",
            subtitle = "Field engineering RF thresholds across 2.4 / 5 / 6 GHz",
            bulletPoints = listOf(
                "Logarithmic power rule: +3 dB = 2x RF power, -3 dB = 0.5x power, -10 dB = 1/10th power.",
                "Enterprise target floors: >= -65 dBm for dense client data, >= -67 dBm for roaming VoIP/video, < -75 dBm = weak/dead-zone risk.",
                "SNR targets (when driver exposes noise floor): >= 25 dB minimum reliable modulation; >= 35-40 dB for highest QAM rates.",
                "2.4 GHz clean layout: 20 MHz width ONLY on channels 1, 6, and 11 to avoid adjacent-channel corruption.",
                "Channel width tradeoff: doubling 20 -> 40 -> 80 -> 160 MHz roughly doubles PHY max while lifting thermal noise floor by ~3 dB per doubling."
            )
        ),
        AdvancedReferenceItem(
            id = "ref_android_limits",
            title = "Android OS Diagnostic & Privacy Guardrails",
            subtitle = "Separating genuine network failures from Android platform restrictions",
            bulletPoints = listOf(
                "Wi-Fi Scan Throttling (Android 9+): foreground apps are capped around 4 scans per 2 minutes unless disabled in Developer Options.",
                "SSID/BSSID Visibility: requires ACCESS_FINE_LOCATION / NEARBY_WIFI_DEVICES permission AND system Location Services toggled ON.",
                "Client MAC Randomization: Android hides permanent hardware MACs from standard SDK callers.",
                "Noise Floor / Link MTU / Ethernet Link Speed: omitted by some SoC kernels/APIs; app displays 'Unavailable' honestly instead of guessing.",
                "Raw Socket Restrictions: non-root Android processes cannot forge arbitrary raw IP headers, affecting certain carrier traceroute hop displays."
            )
        ),
        AdvancedReferenceItem(
            id = "ref_cellular_iperf",
            title = "Carrier 464XLAT, PMTUD & iPerf3 TCP Notes",
            subtitle = "Advanced throughput & path interpretation",
            bulletPoints = listOf(
                "464XLAT (CLAT): IPv6-only cellular networks synthesize local IPv4 packets through a CLAT translator (often visible around 192.0.0.x).",
                "Bandwidth-Delay Product (BDP): single TCP streams stall below link speed on higher-RTT paths; use 4 parallel streams in iPerf3 for high-BDP paths.",
                "Socket Buffer Sizing: iPerf3Engine uses bounded 64 KB upload / 256 KB download buffers so the first 1-second interval cannot show phantom buffer-fill speeds.",
                "App Protocol Scope: built-in iPerf3 Engine runs TCP Control + TCP Data streams (-R reverse supported); UDP jitter benchmarks require external desktop/CLI tools."
            )
        )
    )

    fun findToolById(toolId: String): ToolGuideContent? =
        tools.firstOrNull { it.id == toolId }
}
