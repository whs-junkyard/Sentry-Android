# Sentry-Android

Sentry is an open source exception logger - getsentry.com

This is a Sentry client for Android application forked from [joshdholtz/Sentry-Android](https://github.com/joshdholtz/Sentry-Android) with full stack trace logging from [sounddrop/Sentry-Android](https://github.com/soundrop/Sentry-Android) and I added some stuff over it

- Use [OkHttp](https://square.github.io/okhttp/) instead of plain connection
- Send full exception stack trace
- Send device info

My fork is developed as part of [KUSmartBus](https://play.google.com/store/apps/details?id=th.in.whs.ku.bus) and funded by [Department of Civil Engineering, Faculty of Engineering, Kasetsart University](http://ce.eng.ku.ac.th/main.html).

## Install

Note that I didn't test this with Maven or Gradle, only Eclipse.

1. Right click on package explorer in Eclipse and Import>Existing Android Code into Workspace
2. Right click this project > Java Build Path > Libraries and add the JAR of [OkHttp](https://square.github.io/okhttp/) (tested with version 2.0.0) and [Okio](http://github.com/square/okio), the dependency of OkHttp

## Usage

Put this in your root activity.

````java
PackageInfo pInfo = getPackageManager().getPackageInfo(getPackageName(), 0);
HashMap<String, String> tags = new HashMap<String, String>();
tags.put("version", pInfo.versionName);
tags.put("debug", BuildConfig.DEBUG ? "true" : "false");
Sentry.init(this, "https://app.getsentry.com", "dsn", tags);
````

Don't forget to change sentry server and DSN to your own. Sentry will automatically capture app crashes and report them on next start.

## License

MIT License