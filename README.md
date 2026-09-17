# Central Chat — Android

```kotlin
// settings.gradle.kts
repositories { google(); mavenCentral(); maven("https://static.central.chat/maven") }

// app/build.gradle.kts
implementation("chat.central:centralchat:0.2.1")
```

```kotlin
CentralChat.init(this, entry)   // at app start — warms everything
CentralChat.show(this)          // from your support button
CentralChat.hide()              // optional; the screen closes itself
CentralChat.reset(this)         // your logout — next init is a new visitor
```

`entry` is a **channel key** (`businessId|chatAccountId`, anonymous) or a **mint
user key** your backend signed (that person). One argument, either door.

`minSdk` 24 · deps: `androidx.activity`, `androidx.core` · nothing goes in your
manifest.

### Optional

```kotlin
CentralChat.onReady = { }                     // warm; enable your button here
CentralChat.onError = { (code, message) -> }  // ENTRY_INVALID | TOKEN_EXPIRED | NETWORK | INTERNAL
```

Transport failures retry themselves, including the moment the device is back
online; `show()` refuses a chat that failed, so no browser error page ever
appears. A refused key is never retried — mint another and call `init` again.

Permissions, only for what your chat account offers: `RECORD_AUDIO` +
`MODIFY_AUDIO_SETTINGS` (voice), `ACCESS_*_LOCATION` (location). **Never
`CAMERA`** — photos use the system picker, and declaring it makes Android
enforce it.
