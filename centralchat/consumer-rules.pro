# Consumer rules — applied to any app that links this library, so an integrator
# needs nothing in their own proguard-rules.pro.
#
# There is exactly one constraint, and it is not optional: the bridge class's
# method names ARE the protocol. R8 renames anything it can prove is unreachable
# from Java, and every call into `@JavascriptInterface` methods comes from
# JavaScript — which R8 cannot see. Renaming them does not fail the build and
# does not throw at runtime; it makes every event silently do nothing, which is
# the worst failure mode available.
-keepclassmembers class chat.central.widget.** {
    @android.webkit.JavascriptInterface <methods>;
}

# The public API surface, kept so an app's own reflection (and a stack trace
# worth reading) still resolves the names in this library's docs.
-keep public class chat.central.widget.CentralChat { public *; }
-keep public class chat.central.widget.CentralChat$* { public *; }
-keep public class chat.central.widget.CentralChatError { public *; }
-keep public class chat.central.widget.CentralChatErrorCode { public *; }
