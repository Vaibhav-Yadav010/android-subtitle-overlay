from pathlib import Path

PATH = Path("SubtitlesAppSubrimaProject/app/src/main/java/com/example/subtitles/model/translation/MlKitTranslator.java")
text = PATH.read_text(encoding="utf-8")

if "private PendingTranslation pendingTranslation;" in text:
    print("Translation throttle already present; nothing to patch.")
    raise SystemExit(0)

field_marker = "    private final AtomicInteger requestCounter = new AtomicInteger(0); // Tracks latest request ID\n"
fields = '''    private final AtomicInteger requestCounter = new AtomicInteger(0); // Tracks latest request ID

    // Keep only the newest transcription update while one ML Kit request is running.
    private final Object translationQueueLock = new Object();
    private boolean translationInFlight = false;
    private PendingTranslation pendingTranslation;

    private static final class PendingTranslation {
        final String lastSourceSentence;
        final String text;
        final TranslationCallback callback;

        PendingTranslation(String lastSourceSentence, String text, TranslationCallback callback) {
            this.lastSourceSentence = lastSourceSentence;
            this.text = text;
            this.callback = callback;
        }
    }
'''
if field_marker not in text:
    raise SystemExit("requestCounter marker not found")
text = text.replace(field_marker, fields, 1)

start_marker = "    public void translate(String lastSourceSentence, @NonNull String text, @NonNull TranslationCallback callback) {"
end_marker = "    // ================================================================\n    // Text utility methods (sentence/word splitting)"
start = text.find(start_marker)
end = text.find(end_marker, start)
if start < 0 or end < 0:
    raise SystemExit("translation method markers not found")

replacement = r'''    public void translate(String lastSourceSentence, @NonNull String text, @NonNull TranslationCallback callback) {
        synchronized (translationQueueLock) {
            pendingTranslation = new PendingTranslation(lastSourceSentence, text, callback);
            if (translationInFlight) {
                return;
            }
            translationInFlight = true;
        }

        startNextTranslation();
    }

    private void startNextTranslation() {
        final PendingTranslation request;
        synchronized (translationQueueLock) {
            request = pendingTranslation;
            pendingTranslation = null;
            if (request == null) {
                translationInFlight = false;
                return;
            }
        }

        final int myId = requestCounter.incrementAndGet();
        try {
            if (!isReady()) {
                request.callback.onError(new TranslationException.NotReady());
                finishTranslation();
                return;
            }

            Log.d(TAG, "*************************************************************************************");
            Log.d(TAG, "lastSourceSentence: " + request.lastSourceSentence);
            Log.d(TAG, "text: " + request.text);

            String input = request.lastSourceSentence.isEmpty()
                    ? request.text.trim()
                    : request.lastSourceSentence.trim() + "\n" + request.text.trim();

            Log.d(TAG, "input: " + input);

            if (!useIntermediate) {
                directTranslator.translate(input)
                        .addOnSuccessListener(res -> {
                            if (requestCounter.get() == myId) {
                                String onlyNew = extractNewPortion(
                                        res,
                                        request.text,
                                        sourceLang,
                                        targetLang
                                );
                                request.callback.onResult(res, onlyNew);
                            }
                            finishTranslation();
                        })
                        .addOnFailureListener(e -> {
                            if (requestCounter.get() == myId) {
                                request.callback.onError(new TranslationException.ServiceError(e));
                            }
                            finishTranslation();
                        });
            } else {
                interTranslator.translate(input)
                        .addOnSuccessListener(interRes -> {
                            if (requestCounter.get() != myId) {
                                finishTranslation();
                                return;
                            }

                            if (TranslateLanguage.ENGLISH.equals(safeLanguage(targetLang))) {
                                String onlyNew = extractNewPortion(
                                        interRes,
                                        request.text,
                                        sourceLang,
                                        targetLang
                                );
                                request.callback.onResult(interRes, onlyNew);
                                finishTranslation();
                            } else {
                                directTranslator.translate(interRes)
                                        .addOnSuccessListener(finalRes -> {
                                            if (requestCounter.get() == myId) {
                                                String onlyNew = extractNewPortion(
                                                        finalRes,
                                                        request.text,
                                                        sourceLang,
                                                        targetLang
                                                );
                                                request.callback.onResult(finalRes, onlyNew);
                                            }
                                            finishTranslation();
                                        })
                                        .addOnFailureListener(e2 -> {
                                            if (requestCounter.get() == myId) {
                                                request.callback.onError(new TranslationException.ServiceError(e2));
                                            }
                                            finishTranslation();
                                        });
                            }
                        })
                        .addOnFailureListener(e -> {
                            if (requestCounter.get() == myId) {
                                request.callback.onError(new TranslationException.ServiceError(e));
                            }
                            finishTranslation();
                        });
            }
        } catch (Exception ex) {
            Log.e(TAG, "Unexpected error in translate()", ex);
            request.callback.onError(new TranslationException.ServiceError(ex));
            finishTranslation();
        }
    }

    private void finishTranslation() {
        synchronized (translationQueueLock) {
            if (pendingTranslation == null) {
                translationInFlight = false;
                return;
            }
        }
        startNextTranslation();
    }

'''

text = text[:start] + replacement + text[end:]

assert text.count("public void translate(String lastSourceSentence") == 1
assert text.count("private PendingTranslation pendingTranslation;") == 1
assert text.count("private void startNextTranslation()") == 1
assert text.count("private void finishTranslation()") == 1

PATH.write_text(text, encoding="utf-8")
print("Translation throttle patch validation passed")
