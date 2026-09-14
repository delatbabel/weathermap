# Urban Rain Gauge Network: Cost Comparison & Architecture

This document provides a comprehensive overview and cost breakdown for deploying a screenless, API-accessible rain gauge network across multiple city locations utilizing Wi-Fi and 4G/cellular connections.

---

## Deployment Architecture Overview

To bypass standard consumer smartphone applications and pipe rainfall data directly into a central internet-connected computer via an API, two primary pathways are viable:

### Option A: Ecowitt Combo (Developer-Friendly & Modular)
* **The Concept:** Uses screenless, low-cost hardware that transmits local RF signals to a mini Wi-Fi gateway. The gateway bypasses proprietary cloud ecosystems by natively executing direct HTTP POST requests to a custom target server URL.
* **Connectivity:** Connects directly to localized Wi-Fi. In areas lacking native network access, it pairs seamlessly with an external 4G LTE travel router.
* **Official Website:** [Ecowitt Official Store](https://www.ecowitt.com)

### Option B: Davis EnviroMonitor (Industrial & Self-Contained)
* **The Concept:** High-reliability, ruggedized agricultural equipment built inside weatherproof enclosures with dedicated solar power cells. It aggregates data seamlessly to a proprietary cloud that exposes a well-documented developer REST API.
* **Connectivity:** Integrated cellular modem handles all communication natively without dependency on third-party localized networks.
* **Official Website:** [Davis Instruments EnviroMonitor](https://www.davisinstruments.com/pages/enviromonitor)

---

## Cost Comparison Matrix

| Expense Category | Option A: Ecowitt Combo (Wi-Fi + Added Cellular) | Option B: Davis EnviroMonitor (All-in-One Cellular) |
| :--- | :--- | :--- |
| **Rain Sensor Hardware** | **$57** *(Ecowitt WH40 / WH40H)* | **$150** *(Davis AeroCone Collector)* |
| **Gateway Hardware** | **$32** *(Ecowitt GW1200 USB Dongle)* | **$795** *(Davis 6803 EnviroMonitor Gateway)* |
| **Cellular Capability** | **$40** *(Standard 4G LTE Travel Router)* | **$0** *(Built explicitly into the main gateway)* |
| **One-time Activation Fee** | **$0** | **$35** *(Davis hardware initiation fee)* |
| **Annual Service Plan / API Access** | **$0** *(Completely free local & custom routing)* | **$250 – $350/year** *(Per node for cloud access & API token)* |
| **Total Upfront Cost (Per Site)** | **$89 (Wi-Fi site) / $129 (4G site)** | **$980** |

---

## Custom Target Endpoint Data Routing (Ecowitt)

When using Option A, you configure the GW1200 Gateway's localized browser interface to forward raw datasets to your target machine using the **"Customized Weather Services"** configuration:

```ini
Customized: Enable
Protocol Type: Ecowitt
Server IP / Host Name: [Your central computer's public IP or domain]
Path: /api/rain-receiver
Port: [Your preferred open port, e.g., 80 or 8080]
Upload Interval: 60 - 300 seconds
```

---

## Key Strategic Trade-offs

1. **Scalability & Value:** For a 10-node urban deployment, Option A costs approximately **$890 to $1,290** upfront with zero ongoing API licensing penalties. Option B scales past **$9,800** upfront combined with **$2,500+ recurring annual operational fees**.
2. **Infrastructure Complexity:** Option A relies on lightweight middleware scripts (Python/Node.js) on your host computer to catch incoming server endpoints. Option B manages data parsing downstream on its own clouds, offering structured REST JSON arrays via traditional HTTP GET requests.
