package chat.central.widget.example.java;

import android.app.Activity;
import android.util.Log;

import chat.central.widget.CentralChat;
import chat.central.widget.CentralChatErrorCode;

/**
 * Not used by the demo — it exists to prove the library is callable from plain
 * Java, which a Kotlin-only integration test would never catch.
 */
public final class JavaUsage {

    public static void everything(Activity activity, String entry) {
        CentralChat.setOnReady(() -> Log.i("chat", "ready"));
        CentralChat.setOnError(error -> {
            if (error.getCode() == CentralChatErrorCode.TOKEN_EXPIRED) {
                Log.w("chat", "mint another: " + error.getMessage());
            }
        });

        CentralChat.init(activity, entry);
        CentralChat.init(activity, "business-id", "chat-account-id");
        CentralChat.show(activity);
        CentralChat.hide();
        CentralChat.reset(activity);

        CentralChat.setContainerUrl("https://web.central.chat/widget/container.html");
        Log.i("chat", CentralChat.getContainerUrl());
    }
}
