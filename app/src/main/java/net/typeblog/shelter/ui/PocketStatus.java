package net.typeblog.shelter.ui;

import org.json.JSONObject;

/**
 * One line of state shared by every screen. Policy (Always-on with lockdown) and the actual connection are judged
 * separately: copies may be added only once both are confirmed, nothing is promised before that.
 */
final class PocketStatus {
    enum Tone { GOOD, BUSY, ATTENTION }
    enum Fix { NONE, LINK, ADD_CONNECTION, PROTECTION, CHECK }

    Tone tone;
    String title, detail = "", actionLabel;
    /** Big screen headline and the one word on the proxy card. */
    String headline, word;
    Fix action = Fix.NONE;
    /** Copies may be created only when the proxy is up and the system blocks traffic without it. */
    boolean canAdd;
    /** Why «Добавить» is locked, shown when it is tapped. */
    String lockReason;

    static PocketStatus of(JSONObject state, String linkProblem) {
        PocketStatus s = new PocketStatus();
        boolean linked = state != null;
        boolean confirmed = linked && "CONFIRMED".equals(state.optString("policyState"));
        String connection = linked ? state.optString("connectionState") : "";
        String name = linked ? state.optString("selectedName") : "";
        String type = linked ? state.optString("selectedType") : "";
        boolean selected = linked && !state.optString("selectedId").isEmpty();

        if (!linked && MainActivity.WORK_PAUSED.equals(linkProblem)) {
            s.say("Рабочий профиль выключен", "Пауза"); s.set(Tone.ATTENTION, "Рабочий профиль выключен", "Включи его, Pocket продолжит сам", Fix.LINK, "Включить");
        } else if (!linked) {
            boolean waiting = linkProblem == null || linkProblem.startsWith("Связываем");
            s.say(waiting ? "Связываем профиль…" : "Профиль не связан", waiting ? "Ждём" : "Нет связи");
            s.set(waiting ? Tone.BUSY : Tone.ATTENTION, waiting ? "Связываем профиль…" : "Профиль не связан",
                    waiting ? "Это займёт пару секунд" : linkProblem, waiting ? Fix.NONE : Fix.LINK, "Повторить");
        } else if (!confirmed) {
            s.say("Включи защиту", "Без защиты");
            s.set(Tone.ATTENTION, "Защита не включена", "Без неё копии не выйдут в интернет", Fix.PROTECTION, "Включить");
        } else if (!selected) {
            s.say("Подключи прокси", "Нет прокси");
            s.set(Tone.ATTENTION, "Прокси не добавлен", "Добавь ссылку или купи Proxy", Fix.ADD_CONNECTION, "Добавить");
        } else if ("CONNECTED".equals(connection)) {
            s.say("Всё под защитой", "Онлайн");
            s.set(Tone.GOOD, "Прокси подключён", name + " · " + type, Fix.NONE, null);
            s.canAdd = true;
        } else if ("CONNECTING".equals(connection)) {
            s.say("Подключаемся…", "Ждём");
            s.set(Tone.BUSY, "Подключаемся…", name + " · " + type, Fix.NONE, null);
        } else {
            s.say("Нет связи с прокси", "Нет связи");
            s.set(Tone.ATTENTION, "Нет связи с прокси", connectionText(connection, state.optString("error"))
                    + " Интернет копий закрыт.", Fix.CHECK, "Проверить");
        }
        if (!s.canAdd) s.lockReason = "Добавлять копии можно, когда прокси подключён. Сейчас: " + s.title.toLowerCase(java.util.Locale.ROOT) + ".";
        return s;
    }

    private void say(String headline, String word) { this.headline = headline; this.word = word; }

    private void set(Tone tone, String title, String detail, Fix action, String actionLabel) {
        this.tone = tone; this.title = title; this.detail = detail == null ? "" : detail;
        this.action = action; this.actionLabel = actionLabel;
    }

    static String connectionText(String connection, String error) {
        switch (connection) {
            case "NO_NETWORK": return "Нет доступной сети.";
            case "CONNECTING": return "Подключаемся.";
            case "CONNECTED": return "Подключено.";
            default: return error == null || error.isEmpty() ? "Сервер не отвечает." : error;
        }
    }
}
