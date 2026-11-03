package com.deskdash;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;

import java.util.ArrayList;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Voice commands in Russian through the phone's speech recognition: "включи свет на кухне", "выключи всё",
 * "яркость лампы 30 процентов", "режим работа", "таймер на 10 минут", "помодоро", "погода", "что сегодня",
 * "пауза", "следующий трек", "громче". Answers are spoken when the phone has a voice (TextToSpeech).
 */
final class Voice {
    interface Host {
        Map<String, HomeDevice> devices();

        /** "on", "off", "run" or "brightness" with a value. */
        void device(HomeDevice d, String action, int value);

        void allOff();

        boolean mode(String name);

        void timer(String label, long ms);

        void pomodoro();

        /** "play", "pause", "next", "prev", "louder", "quieter". */
        void media(String what);

        String weather();

        String agenda();

        void say(String text);
    }

    interface Listener {
        void partial(String text);

        void done(String text);

        void failed(String why);
    }

    private static final Pattern TIMER = Pattern.compile("(\\d+|[а-яё]+)\\s*(сек|мин|час)");
    private static final Pattern PERCENT = Pattern.compile("(\\d+)\\s*(%|процент)");
    private static final String[][] NUMBERS = {{"одну", "1"}, {"один", "1"}, {"две", "2"}, {"два", "2"}, {"три", "3"},
            {"четыре", "4"}, {"пять", "5"}, {"шесть", "6"}, {"семь", "7"}, {"восемь", "8"}, {"девять", "9"},
            {"десять", "10"}, {"пятнадцать", "15"}, {"двадцать", "20"}, {"тридцать", "30"}, {"сорок", "40"},
            {"полчаса", "30"}, {"час", "1"}};

    private SpeechRecognizer recognizer;

    static boolean available(Context c) {
        return SpeechRecognizer.isRecognitionAvailable(c);
    }

    void listen(Context c, Listener l) {
        stop();
        recognizer = SpeechRecognizer.createSpeechRecognizer(c);
        recognizer.setRecognitionListener(new RecognitionListener() {
            @Override
            public void onReadyForSpeech(Bundle b) {}

            @Override
            public void onBeginningOfSpeech() {}

            @Override
            public void onRmsChanged(float v) {}

            @Override
            public void onBufferReceived(byte[] b) {}

            @Override
            public void onEndOfSpeech() {}

            @Override
            public void onError(int e) {
                l.failed(e == SpeechRecognizer.ERROR_NO_MATCH || e == SpeechRecognizer.ERROR_SPEECH_TIMEOUT ? "Не расслышал"
                        : e == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ? "Нет доступа к микрофону"
                        : e == SpeechRecognizer.ERROR_NETWORK || e == SpeechRecognizer.ERROR_NETWORK_TIMEOUT ? "Нет сети для распознавания"
                        : "Распознавание не сработало (" + e + ")");
            }

            @Override
            public void onResults(Bundle b) {
                ArrayList<String> r = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                l.done(r == null || r.isEmpty() ? "" : r.get(0));
            }

            @Override
            public void onPartialResults(Bundle b) {
                ArrayList<String> r = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (r != null && !r.isEmpty()) l.partial(r.get(0));
            }

            @Override
            public void onEvent(int t, Bundle b) {}
        });
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
        recognizer.startListening(i);
    }

    void stop() {
        if (recognizer != null) {
            try {
                recognizer.destroy();
            } catch (RuntimeException ignored) {
                // already gone
            }
            recognizer = null;
        }
    }

    // ---------------------------------------------------------------- understanding

    /** Does what the phrase asks; returns what was done (or "Не понял…") for the screen. */
    static String handle(String phrase, Host h) {
        String s = phrase.toLowerCase(new Locale("ru")).replace('ё', 'е').trim();
        if (s.isEmpty()) return "Не расслышал";
        for (String[] n : NUMBERS) s = s.replaceAll("(?U)\\b" + n[0] + "\\b", n[1]); // (?U): Cyrillic words have boundaries too

        if (s.contains("помодоро")) {
            h.pomodoro();
            return say(h, "Помодоро: 25 минут работы");
        }
        if (s.contains("таймер") || s.startsWith("засеки")) {
            Matcher m = TIMER.matcher(s);
            if (m.find() && m.group(1).matches("\\d+")) {
                int n = Integer.parseInt(m.group(1));
                long ms = m.group(2).startsWith("сек") ? n * 1000L : m.group(2).startsWith("час") ? n * 3_600_000L : n * 60_000L;
                String label = n + " " + (m.group(2).startsWith("сек") ? "сек" : m.group(2).startsWith("час") ? "ч" : "мин");
                h.timer(label, ms);
                return say(h, "Таймер на " + label);
            }
            return say(h, "На сколько поставить таймер?");
        }
        if (s.startsWith("режим") || s.contains(" режим ")) {
            String name = s.substring(s.indexOf("режим") + 5).trim();
            return say(h, h.mode(name) ? "Режим «" + name + "»" : "Нет режима «" + name + "»");
        }
        if (s.contains("погода") || s.contains("на улице")) return say(h, h.weather());
        if (s.contains("что сегодня") || s.contains("календар") || s.contains("встреч")) return say(h, h.agenda());
        if (s.contains("громче")) {
            h.media("louder");
            return "Громче";
        }
        if (s.contains("тише")) {
            h.media("quieter");
            return "Тише";
        }
        if (s.contains("следующ")) {
            h.media("next");
            return "Следующий трек";
        }
        if (s.contains("предыдущ") || s.contains("назад")) {
            h.media("prev");
            return "Предыдущий трек";
        }
        if (s.equals("пауза") || s.contains("останови музыку") || s.contains("стоп")) {
            h.media("pause");
            return "Пауза";
        }
        if (s.contains("играй") || s.contains("включи музыку") || s.equals("плей")) {
            h.media("play");
            return "Играю";
        }
        if (s.contains("выключи все") || s.contains("выключи весь свет")) {
            h.allOff();
            return say(h, "Выключаю всё");
        }
        Matcher pm = PERCENT.matcher(s);
        if (pm.find() || s.startsWith("яркость")) {
            HomeDevice d = find(h, s.replace("яркость", ""));
            if (d != null && pm.find(0)) {
                int v = Math.max(1, Math.min(100, Integer.parseInt(pm.group(1))));
                h.device(d, "brightness", v);
                return say(h, d.name + ": " + v + " процентов");
            }
        }
        boolean on = s.startsWith("включи") || s.startsWith("зажги") || s.startsWith("открой");
        boolean off = s.startsWith("выключи") || s.startsWith("погаси") || s.startsWith("закрой");
        boolean run = s.startsWith("запусти") || s.startsWith("сценарий");
        if (on || off || run) {
            HomeDevice d = find(h, s.replaceFirst("^\\S+\\s*", ""));
            if (d == null) return say(h, "Не нашёл такое устройство");
            h.device(d, run || d.kind == HomeDevice.SCENE ? "run" : on ? "on" : "off", 0);
            return say(h, (run || d.kind == HomeDevice.SCENE ? "Запускаю " : on ? "Включаю " : "Выключаю ") + d.name);
        }
        return say(h, "Не понял: «" + phrase + "»");
    }

    private static String say(Host h, String text) {
        h.say(text);
        return text;
    }

    /** The device whose name fits the words best: all words of its name heard, the longest such name wins. */
    static HomeDevice find(Host h, String words) {
        String w = words.replaceAll("(?U)\\b(свет|лампу|лампа|на|в|во|у|и|пожалуйста)\\b", " ").replaceAll("\\s+", " ").trim();
        HomeDevice best = null;
        int bestScore = 0;
        for (HomeDevice d : h.devices().values()) {
            String n = d.name.toLowerCase(new Locale("ru")).replace('ё', 'е');
            int score = 0;
            for (String part : n.split("\\s+")) {
                if (part.length() < 3) continue;
                String stem = part.length() > 4 ? part.substring(0, part.length() - 2) : part;
                if (w.contains(stem)) score += part.length();
            }
            String room = d.room.toLowerCase(new Locale("ru"));
            if (!room.isEmpty() && w.contains(room.length() > 4 ? room.substring(0, room.length() - 2) : room)) score += 2;
            if (score > bestScore) {
                best = d;
                bestScore = score;
            }
        }
        return best;
    }
}
