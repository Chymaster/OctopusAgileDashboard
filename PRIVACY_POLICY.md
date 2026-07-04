# Privacy Policy

**OctopusDashboard**

Last updated: 4 July 2026

## Introduction

OctopusDashboard ("the App") is an Android application that displays electricity pricing and consumption data from your Octopus Energy account. This privacy policy explains what data the App accesses, how it is used, and how it is protected.

## Data We Collect

### Data You Provide

The App requires you to manually enter the following information in the Settings screen:

- **Octopus Energy API key** — used to authenticate requests to the Octopus Energy API
- **MPAN (Meter Point Administration Number)** — your electricity supply point identifier
- **Meter serial number** — your meter's unique serial number
- **Grid Supply Point (GSP)** — your electricity distribution region code
- **Product code** — your Octopus Energy tariff product code

All of this data is entered voluntarily by you. The App does not request, collect, or transmit any of this data automatically.

### Data Retrieved From External Services

The App retrieves the following data from third-party APIs:

- **Electricity pricing data** — half-hourly unit rates and standing charges from the Octopus Energy API (`api.energy.octopus.energy`)
- **Energy consumption data** — your historical half-hourly electricity consumption from the Octopus Energy API
- **Meter point information** — tariff and meter details from the Octopus Energy API
- **Fuel mix data** — current electricity generation source breakdown from the Carbon Intensity API (`api.carbonintensity.org.uk`), a public UK government service

### Data Stored Locally on Your Device

The App stores the following data locally on your device using Android DataStore and Room database:

- Your API key, MPAN, meter serial number, GSP, and product code (DataStore preferences)
- Cached electricity pricing data (Room database)
- Cached consumption history (Room database)
- Cached standing charge information (Room database)
- App settings and display preferences (DataStore preferences)

No data is stored on any external servers. All data remains on your device.

### Data We Do NOT Collect

The App does **not** collect:

- Your name, email address, or any personal identifiers
- Your location or GPS coordinates
- Your device identifiers or advertising IDs
- Usage analytics or crash reports
- Any data from other apps on your device
- Contacts, photos, files, or any other personal content

## How Your Data Is Used

Your data is used solely to:

1. **Authenticate with Octopus Energy** — your API key is sent with HTTP requests to the Octopus Energy API to retrieve your account-specific data
2. **Display pricing information** — tariff and pricing data is shown in charts and gauges
3. **Display consumption history** — your electricity usage is shown in cost analysis views
4. **Display fuel mix** — the current UK generation mix is shown as a pie chart

The App does not use your data for advertising, profiling, analytics, or any purpose other than displaying your energy information to you.

## Data Sharing

The App does **not** share your data with any third parties. Specifically:

- **No analytics services** — the App does not integrate Google Analytics, Firebase, or any other analytics SDK
- **No advertising networks** — the App contains no advertising
- **No data brokers** — no data is sold, rented, or transferred to any third party
- **No social media integrations** — the App does not connect to any social networks

The only external services the App communicates with are:

1. **Octopus Energy API** (`api.energy.octopus.energy`) — to retrieve your energy data. Your API key is transmitted as an HTTP Basic Authentication header. This is necessary for the App to function. See Octopus Energy's privacy policy for how they handle data.
2. **Carbon Intensity API** (`api.carbonintensity.org.uk`) — a public UK government API that provides no user-identifiable data. No credentials or personal information are sent to this service.

## Data Security

- **Encryption in transit** — all network requests to the Octopus Energy API use HTTPS (TLS), encrypting your API key and data during transmission
- **Local storage** — data stored on your device is protected by Android's standard application sandboxing. The API key and credentials are stored in Android DataStore, which is accessible only to the App
- **No cloud storage** — the App does not upload any data to cloud services

## Data Retention and Deletion

- **Locally stored data persists** until you uninstall the App or clear its data through Android Settings
- **To delete all stored data**: go to Android Settings → Apps → OctopusDashboard → Storage → Clear Data, or uninstall the App
- **Cached API data** (prices, consumption, standing charges) is retained locally to reduce network requests and enable offline viewing. Old cached data may be automatically purged when new data is fetched

## Permissions

The App requests the following Android permissions:

| Permission | Purpose |
|---|---|
| `INTERNET` | Required to make API requests to Octopus Energy and Carbon Intensity APIs |
| `ACCESS_NETWORK_STATE` | Required to check network connectivity before making API requests |

The App does **not** request access to location, camera, microphone, contacts, storage, phone, or any other sensitive permissions.

## Children's Privacy

The App is not directed at children under the age of 13 and does not knowingly collect personal information from children.

## Changes to This Policy

We may update this privacy policy from time to time. Changes will be reflected by updating the "Last updated" date at the top of this policy. Continued use of the App after changes constitutes acceptance of the updated policy.

## Contact

If you have questions about this privacy policy or the App's data practices, please contact:

- **Developer**: Chymaster
- **Email**: [YOUR_EMAIL_ADDRESS]

---

*This privacy policy is provided in compliance with [Google Play Store developer policies](https://support.google.com/googleplay/android-developer/answer/10144311).*
