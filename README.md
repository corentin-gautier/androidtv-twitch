## Android TV Twitch Replacement

The official twitch app on android tv is attrocious : laggy, buggy and full of ads, this is a replacement for it. 

Goal : a twich app for android tv and chromecast that doesn't lag, doesn't show ads, that you can connect to easily with your twitch account.

Done : when I can log in to it on my local chromecast and watch streams without ads

How to test : the chromecast on my local network

## Build and install

Requires a JDK and the Android SDK. On the TV, turn on Settings › System › Developer options › Wireless debugging and pair it once with `adb pair`.

```sh
scripts/install-tv.sh
```

The script builds the release APK, installs it on the TV found on the local network and launches it.
