# Репетиция — Android-прототип

Приложение: взять трек с телефона, один раз разложить нейросетью на **четыре дорожки**, играть под метроном. Если крутите BPM метронома — вместе с ним меняется скорость трека.

Это папка **проекта Android Studio**. Git не используется. Версия **0.3.2**.

## Что реально стоит в этом срезе

**Модель: HT-Demucs (`htdemucs`), один ONNX-файл, 4 стемы.** Не mid/side, не 6-stem гитара.

| Файл | Что это |
| --- | --- |
| `stems/vocals.wav` | вокал |
| `stems/drums.wav` | ударные |
| `stems/bass.wav` | бас |
| `stems/other.wav` | «Музыка»: всё остальное, **включая гитару и клавиши** |

Порядок выходов модели: `drums, bass, other, vocals`. Веса: `htdemucs_fp16weights.onnx` (~166 МБ), ONNX Runtime CPU. Новые сплиты — **IEEE float32 WAV** 44.1 кГц. Плеер стемов **не** декодирует эти WAV через ExoPlayer: `StemMixMediaSource` отдаёт уже сведённый PCM 16-bit. Старые 16-bit WAV тоже читаются.

Mute вокала = gain 0 у вокала. Гитара отдельно не выходит — она в «Музыке».

## Почему 0.3.1 ломал метроном

1. **Клики не на долях.** Сетка считалась через `ceil` в `double` плюс `remainderIndex` через границу буфера: клик доигрывался с кадра 0 **и** в том же буфере стартовал заново. Доли плыли относительно `presentationTimeUs`. Сейчас [BeatClock]: целые микросекунды от t=0 по **original BPM**. Клики мешаются в media time **до** Sonic, поэтому после `speed = metronomeBpm / originalBpm` остаются на музыкальных битах. Handler-таймера нет.
2. **Метроном ON во время Play останавливал звук.** Пока клики выключены, sink отдавал **буфер декодера** в `DefaultAudioSink`. Sink запоминает identity (`inputBuffer == buffer`) и не принимает другой ByteBuffer, пока не доест текущий. Включение кликов подсовывало `mixBuffer` → `IllegalArgumentException` → ExoPlayer в ошибке, `play()` ничего не делает, пока не `load()` (переключение на «4 стемы»). Сейчас [MetronomePcmPipe] **всегда** копирует PCM в свой буфер, даже если клики выключены. Toggle — только флаг, без stop/prepare.

Оригинал и стемы по-прежнему один ExoPlayer. Клики поверх любого таба.

## Тесты (JVM, без эмулятора)

```
./gradlew :app:testDebugUnitTest
```

Как `vendor/bin/phpunit`: крутит JUnit на компьютере, телефон не нужен. Есть кейсы: доли на media time при originalBpm; toggle enabled «на ходу» без reload и без rewind декодера; микс не обнуляет музыку, когда клики включаются.

## Скопировать и собрать APK на Windows

Код лежит в store Cursor. Studio у вас часто открывает копию, например `c:\Users\zador\rehearsal`.

1. Скопируйте **всю** эту папку (там, где `settings.gradle.kts`) поверх копии Android Studio.
2. File → Sync Project with Gradle Files.
3. **Build → Generate App Bundles or APKs → Generate APKs** (debug).
4. Поставьте новый APK **0.3.2**. В 0.3.1 метроном сбивался с долей и глушил Play.

Если Kotlin-демон падает (`kotlin compile daemon is ready`) — в `gradle.properties` уже `kotlin.compiler.execution.strategy=in-process`.

## Как открыть проект с нуля

1. Android Studio, SDK 35.
2. File → Open → эта папка с `settings.gradle.kts`.
3. Gradle Sync. Первый раз качается Compose, Media3, Room, ONNX Runtime.

## Телефон

USB-отладка → Run, либо APK. Минимум Android 8.0. Для модели нужен интернет при первом split.

## Что нажать

1. **+** → аудио с телефона. Копия: `files/tracks/<id>/original.*`.
2. Экран обработки: скачивание модели, чанки по 7.8 с. Можно уйти.
3. «Слушать оригинал» — пока считается.
4. Когда «Готово»: микшер 4 фейдера, Mute/Solo, метроном на часах плеера.

Если скачивание модели сорвалось, положите `htdemucs_fp16weights.onnx` в  
`Android/data/com.rehearsal.app/files/models/`.

## Стек

Kotlin, Jetpack Compose, Media3 ExoPlayer, Room, WorkManager, ONNX Runtime, HT-Demucs.

Вне скоупа: YouTube, облако, iOS, аккорды, git, 6-stem гитара.
