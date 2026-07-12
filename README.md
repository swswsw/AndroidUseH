# HoloMobile

HoloMobile (formerly AndroidUseH) is a MobileUse agent and an intelligent Android UI Automation Agent powered by the Holo AI API. It enables autonomous interaction with Android applications by analyzing screen content and UI structure to perform tasks as specified by the user.

## Features

- **Autonomous Agent**: Leverages LLMs to plan and execute actions on Android.
- **Accessibility Integration**: Uses Android's Accessibility Service to observe the UI tree and perform precise interactions (click, type, swipe).
- **Vision-Augmented**: Combines UI tree data with screen captures for robust understanding of the application state.
- **Holo AI Integration**: Built to work with `hcompany.ai` models for high-performance mobile agent capabilities.

## Getting Started

### Prerequisites

- An Android device or emulator running Android 10 (API level 29) or higher.
- A Holo API key from [hcompany.ai](https://hcompany.ai/).

### Installation

1. Clone the repository.
2. Open the project in Android Studio.
3. Build and run the `app` module on your device.

### Configuration

1. **API Key**: Launch the app and enter your Holo API key in the setup screen.
2. **Accessibility Service**: 
   - Tap "Open Accessibility Settings" in the app.
   - Find and enable "HoloMobile Assistant".
   - Grant the necessary permissions (this is required for the agent to see the screen and perform actions).

## How it Works

1. **State Observation**: The app captures the current screen (bitmap) and the UI accessibility tree.
2. **Normalization**: UI elements and coordinates are normalized to a standard 0-1000 coordinate system.
3. **Action Planning**: Data is sent to the Holo AI model, which returns the next logical action (e.g., "click at (500, 200)").
4. **Execution**: The Accessibility Service executes the action on the device.
5. **Feedback Loop**: The process repeats until the task is marked as "done".

## Project Structure

- `app/src/main/java/org/goldenpass/androiduseh/`:
    - `MainActivity.kt`: Setup UI and configuration.
    - `HoloAgent.kt`: Integration with the Holo AI API.
    - `UIAgentAccessibilityService.kt`: Core service for UI interaction and screen capture.
    - `BitmapUtils.kt`: Image processing utilities.
    - `SecurityManager.kt`: Secure storage for API keys.

## Credits

This project uses [AndroidUse](https://github.com/swswsw/AndroidUse) as an example and reference.

## License

[Add License Information Here]
