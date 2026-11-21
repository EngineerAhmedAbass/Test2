package com.tvremote.app;

import java.io.IOException;

/** اللي أي نوع تليفزيون لازم يعمله (Android TV أو LG). */
public interface Remote {
    /** بيوصل ويفضل شغال لحد ما الاتصال يقفل (شغّلها في Thread). */
    void runRemote(String host) throws IOException;

    boolean isConnected();

    void disconnect();

    /** keyCode = أرقام KeyEvent بتاعة أندرويد. */
    void sendKey(int keyCode, int direction) throws IOException;

    void sendText(String text) throws IOException;

    void launch(String link) throws IOException;
}
