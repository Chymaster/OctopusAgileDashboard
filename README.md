# OctopusDashboard

An Android dashboard app for monitoring Octopus Energy agile electricity tariffs, consumption data, and carbon intensity.

## Features

- Real-time agile electricity pricing display
- Historical consumption tracking
- Carbon intensity monitoring
- Price forecasting
- Interactive charts and visualizations
- Material 3 design with Jetpack Compose

## Tech Stack

- **Language**: Kotlin 2.2
- **UI**: Jetpack Compose with Material 3
- **Architecture**: Clean Architecture (data/domain/ui layers)
- **DI**: Hilt
- **Database**: Room
- **Networking**: Retrofit + OkHttp
- **Charts**: Vico
- **Serialization**: Kotlinx Serialization
- **Preferences**: DataStore

## Building

1. Clone the repository
2. Open in Android Studio
3. Sync Gradle and run on device/emulator

**Requirements:**
- Android Studio with AGP 9.2.1+
- JDK 17+
- Android SDK 36 (compileSdk)
- Min SDK 26 (Android 8.0)

## License

This project is licensed under the GNU General Public License v3.0 - see the [LICENSE](LICENSE) file for details.

### GPLv3 and Google Play Store

This app is licensed under GPLv3 and is intended for distribution on the Google Play Store. The source code is publicly available on GitHub, satisfying GPLv3's source distribution requirements.

**Note**: GPLv3's anti-tivoization provisions (Section 6) require that users be able to install modified versions on their devices. Users can build and install the APK directly from source using Android Studio.

## Contributing

Contributions are welcome! Please feel free to submit a Pull Request.

## Acknowledgments

- [Octopus Energy API](https://octopus.energy/dashboard/developer/) for providing the data
- [Carbon Intensity API](https://carbonintensity.org.uk/) for carbon data
