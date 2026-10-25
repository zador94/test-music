# Репетиция — Android-прототип

Приложение: взять трек с телефона, один раз разложить нейросетью на **четыре дорожки**, играть под метроном. Если крутите BPM метронома — вместе с ним меняется скорость трека.

Это папка **проекта Android Studio**. Репозиторий: https://github.com/zador94/test-music. Версия **0.3.10**.

## Что реально стоит в этом срезе

**Модель: HT-Demucs (`htdemucs`), один ONNX-файл, 4 стемы.** Не mid/side, не 6-stem гитара.

| Файл | Что это |
| --- | --- |
| `stems/vocals.wav` | вокал |
| `stems/drums.wav` | ударные |
| `stems/bass.wav` | бас |
| `stems/other.wav` | «Музыка»: всё остальное, **включая гитару и клавиши** |

Порядок выходов модели: `drums, bass, other, vocals`. Веса: `htdemucs_fp16weights.onnx` (~166 МБ), ONNX Runtime CPU. Новые сплиты — **IEEE float32 WAV** 44.1 кГц. Плеер стемов **не** декодирует эти WAV через ExoPlayer: `StemMixMediaSource` отдаёт уже сведённый PCM 16-bit. Старые 16-bit WAV тоже читаются.

Mute вокала = gain 0 у вокала, на **следующем** смешанном буфере (десятки мс, либо `AudioTrack.getMinBufferSize` на эмуляторе), не через секунду очереди ExoPlayer. Гитара отдельно не выходит — она в «Музыке». Метроном (как веб): **отдельный** `ClickTrack` / AudioTrack, не тихий mix в stem PCM. Gain «раз» 2.2, остальные доли 1.65; downbeat длиннее/ниже. Lookahead 0.12 с wall × speed.

После разбора рядом со стемами пишется `tracks/{id}/analysis.json`: BPM, `beats[]`, duration из длины PCM. Поле «Темп (оригинальный BPM)» заполняется детектором (не 120 по умолчанию). Метроном кликает по `beats[i]` в media time. Строку темпа по-прежнему можно поправить руками.

## Почему 0.3.9 крашилось при включении клика

`ClickTrack` читал `ExoPlayer.currentPosition` / `playWhenReady` с потока `metronome-click`. Media3 разрешает плеер только с одного потока (main) → `IllegalStateException` → процесс умирает в момент Switch метронома. С 0.3.10 часы клика — снимок с main (`ClickClock`: songTime = origin + elapsed × speed), worker ExoPlayer не трогает.

## Почему 0.3.8 метроном всё ещё не слышно

Веб не мешает клик в song buffer. До 0.3.8 Android писал клик в тот же PCM, что стемы (`mixInPlace` в `MetronomePcmPipe`). На устройстве этот буфер часто не доходил до колонок (`isPcm==false`, mime не `audio/raw`, либо 0.78 peak тонул в барабанах). С 0.3.9 клик — второй `AudioTrack` (`ClickTrack` / `ClickLayer`), gain «раз» 2.2, lookahead 0.12 с. Stem pipe кликов не содержит.

## Почему 0.3.3 глох при движении слайдера метронома

Каждый тик слайдера звал `setPlaybackSpeed`. DefaultAudioSink на Sonic сначала drain (`queueEndOfStream`). На **снижении** скорости Sonic отдаёт больше PCM, drain не успевает, `handleBuffer` вечно false. Звук пропадает, `playWhenReady` уже true, Play ничего не делает, пока не `load()` (смена таба). Сейчас скорость на AudioTrack (без drain), плюс гейт: не чаще ~100 мс, последний BPM не теряется. Фикс строки «Темп» из 0.3.3 на месте.

## Почему 0.3.2 падал при правке строки «Темп»

Первый символ в поле оригинального BPM (не слайдер). «120»→«1»/«12» парсилось как число и подставлялось в диапазон соседнего слайдера: для 12 это **30..24**. `coerceIn` с min>max — force-close. Сейчас черновик локальный, в плеер уходит по Done/unfocus, и только 30–240.

## Почему 0.3.1 ломал метроном

1. **Клики не на долях.** Сетка считалась через `ceil` в `double` плюс `remainderIndex` через границу буфера: клик доигрывался с кадра 0 **и** в том же буфере стартовал заново. Доли плыли относительно `presentationTimeUs`. Сейчас [BeatClock]: целые микросекунды от t=0 по **original BPM**. Клики мешаются в media time **до** Sonic, поэтому после `speed = metronomeBpm / originalBpm` остаются на музыкальных битах. Handler-таймера нет.
2. **Метроном ON во время Play останавливал звук.** Пока клики выключены, sink отдавал **буфер декодера** в `DefaultAudioSink`. Sink запоминает identity (`inputBuffer == buffer`) и не принимает другой ByteBuffer, пока не доест текущий. Включение кликов подсовывало `mixBuffer` → `IllegalArgumentException` → ExoPlayer в ошибке, `play()` ничего не делает, пока не `load()` (переключение на «4 стемы»). Сейчас [MetronomePcmPipe] **всегда** копирует PCM в свой буфер, даже если клики выключены. Toggle — только флаг, без stop/prepare.

Оригинал и стемы по-прежнему один ExoPlayer. Клики — второй AudioTrack (`ClickTrack`), поэтому слышны и при mute всех стемов, и когда sink mime не `audio/raw`. С 0.3.5 доли из `beats[]`; без analysis — сетка `BeatClock`.

## Тесты (JVM, без эмулятора)

```
./gradlew :app:testDebugUnitTest
```

Как `vendor/bin/phpunit`: крутит JUnit на компьютере, телефон не нужен. Есть кейсы: mute на текущем mix-буфере; стемы замучены → клик всё равно в `ClickLayer`; stem pipe не содержит кликов; speed-up не дропается гейтом; software stretch >1×. JVM не слышит колонки: два AudioTrack на BlueStacks этими тестами не проверяются.

## Скопировать и собрать APK на Windows

Код лежит в store Cursor. Studio у вас часто открывает копию, например `c:\Users\zador\rehearsal`.

1. Скопируйте **всю** эту папку (там, где `settings.gradle.kts`) поверх копии Android Studio.
2. File → Sync Project with Gradle Files.
3. **Build → Generate App Bundles or APKs → Generate APKs** (debug).
4. Поставьте новый APK **0.3.10**. Вкладка «4 стемы» должна звучать. Метроном ON не должен крашить приложение. Клик поверх ударных (drums solo, громкость не в нуле). Слайдер метронома **вверх** ускоряет трек, вниз — замедляет.

Если Kotlin-демон падает (`kotlin compile daemon is ready`) — в `gradle.properties` уже `kotlin.compiler.execution.strategy=in-process`.

Если Gradle пишет `kspDebugUnitTestKotlin FAILED` и `lateinit property cleanFilenames has not been initialized` — это **кэш KSP**, не метроном и не «Fix with AI». В `gradle.properties` уже `ksp.incremental=false`. Дальше на Windows:

1. Не нажимать Studio «Fix with AI».
2. **Build → Clean Project**.
3. Закрыть Studio. Удалить папку `c:\Users\zador\rehearsal\app\build`.
4. Скопировать проект из AgentStores **поверх** `c:\Users\zador\rehearsal`.
5. File → Settings → Build → Build Tools → Gradle → Gradle JDK: **jbr-21**.
6. File → Sync Project with Gradle Files, затем снова тесты или Generate APKs.

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
4. Когда «Готово»: микшер 4 фейдера, Mute/Solo, метроном на часах плеера. На «Фонари» исходный BPM должен быть около **102**, не 120. Вкладка **4 стемы**, Mute вокала — вокал пропадает сразу (не через 1–2 с). Метроном ON, громкость телефона не в нуле — клик слышен поверх ударных/полного микса, «раз» громче. Speed = метроном / этот BPM, как раньше.

Если скачивание модели сорвалось, положите `htdemucs_fp16weights.onnx` в  
`Android/data/com.rehearsal.app/files/models/`.

## Стек

Kotlin, Jetpack Compose, Media3 ExoPlayer, Room, WorkManager, ONNX Runtime, HT-Demucs.

Вне скоупа: YouTube, облако, iOS, аккорды, git, 6-stem гитара.
