# Agent, этап 1: Gemma 4 E2B + голос + живучий сервис

## Сборка
- GitHub: залейте папку в репозиторий. Actions соберёт debug-APK (Actions -> последний запуск -> Artifacts).
- Локально: откройте папку в Android Studio (Gradle wrapper не нужен, студия предложит свой).
  Если собираете из консоли: `gradle assembleDebug` (Gradle 8.7, JDK 17).

## Модель
1. Скачайте `gemma-4-E2B-it.litertlm` (Hugging Face: litert-community/gemma-4-E2B-it-litert-lm, ~2.6 ГБ).
2. Запустите приложение один раз (создаст папку), затем:
   adb push gemma-4-E2B-it.litertlm /sdcard/Android/data/com.example.agent/files/
3. Дайте разрешения, нажмите «Отключить экономию батареи», дождитесь «Модель: готово».
4. «Говорить» -> сказать -> «Стоп».

## Что проверить в первую очередь
- minSdk библиотеки litertlm-android: если билд ругается на manifest merger, поднимите minSdk в app/build.gradle.kts
  (скорее всего потеряете Android 9).
- Версия Kotlin: если библиотека собрана более новым Kotlin, поднимите версию плагина в build.gradle.kts.
- Формат аудио для Content.AudioBytes (здесь PCM16 16kHz mono без заголовка). Если ответ странный,
  попробуйте WAV-заголовок и/или фрагменты до ~30 с.
- Логи: adb logcat -s ModelHost
