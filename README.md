# Репетиция — Android-прототип

Приложение: взять трек с телефона, один раз разложить нейросетью на **четыре дорожки**, играть под метроном. Если крутите BPM метронома — вместе с ним меняется скорость трека.

Это папка **проекта Android Studio**. Репозиторий: https://github.com/zador94/test-music. Версия **0.3.51**.

## Что реально стоит в этом срезе

**Модель: HT-Demucs (`htdemucs`), один ONNX-файл, 4 стемы.** Не mid/side, не 6-stem гитара.

| Файл | Что это |
| --- | --- |
| `stems/vocals.wav` | вокал |
| `stems/drums.wav` | ударные |
| `stems/bass.wav` | бас |
| `stems/other.wav` | «Музыка»: всё остальное, **включая гитару и клавиши** |

Порядок выходов модели: `drums, bass, other, vocals`. Веса: `htdemucs_fp16weights.onnx` (~166 МБ), ONNX Runtime CPU. Длинный трек идёт **окнами модели 7.8 с** с overlap-add: mix на диске, в RAM только текущий сегмент и кольцо аккумулятора, готовый STRIDE сразу в stem WAV. Новые сплиты — **IEEE float32 WAV** 44.1 кГц. Плеер стемов **не** декодирует эти WAV через ExoPlayer: `StemMixMediaSource` отдаёт уже сведённый PCM 16-bit. Старые 16-bit WAV тоже читаются.

Mute вокала = gain 0 у вокала, на **следующем** смешанном буфере (десятки мс, либо `AudioTrack.getMinBufferSize` на эмуляторе). Гитара отдельно не выходит — она в «Музыке».

## Метроном

Один способ найти долю и один способ её нарисовать.

**Доля — `ClickGrid`.** Автокорреляция в 55–180 BPM. Октаву вниз (155 → 77) корпус малого не выбирает: её решает низ бочки. Если малый стоит ровно вдвое чаще бочки, или вместе с ним удары ровно стоят на доле в 1.5 раза чаще, берётся эта четверть (102, а не 68 и не половина). Медленная подсказка микса быстрый темп не заменяет. Цепочка ударов может поправить цифру не больше чем на 1.5 BPM. Фаза — атака низа бочки; щелчок сверху её не сдвигает. `beats[]` — те же равные четверти. `kickGridVersion` **29**: старше — пересчёт по `drums.wav` при открытии.

**Клик — `MetronomeClickEngine`.** Ровная сетка `фаза + n × (60 / BPM)` в том же PCM, что музыка, после WSOLA. `beats[]` не читает. Один AudioTrack, всегда 1×. `TIME_UNSET` не рисует t=0. Громкость `CLICK_VOLUME` 2.2, не зависит от mute/solo стемов. Слайдер «Сдвиг клика, мс» (−500…+500) — запас на задержку аппарата, по умолчанию 0.

Подпись «Темп» — две цифры после точки. Speed = метроном / этот BPM.

## Тесты

```
./gradlew --console=plain :app:assembleDebug :app:testDebugUnitTest
```

`:app:testDebugUnitTest` не доказывает, что APK соберётся. JVM не слышит колонки: тесты проверяют, что синтетическая бочка даёт свой BPM и фазу, и что щелчок попадает в PCM, который уходит в sink.

## Скопировать и собрать APK на Windows

Код лежит в store Cursor. Studio у вас часто открывает копию, например `c:\Users\zador\rehearsal`.

1. Скопируйте **всю** эту папку (там, где `settings.gradle.kts`) поверх копии Android Studio.
2. File → Sync Project with Gradle Files.
3. **Build → Generate App Bundles or APKs → Generate APKs** (debug).
4. Поставьте новый APK **0.3.51**. Плеер — только стемы. Длинный трек разбирается окнами 7.8 с на диск. Старый разбор с `kickGridVersion` < 29 пересчитается по ударным. Клик — ровная сетка, сдвиг **0**. Слайдер метронома вверх и вниз меняет темп.

Если Kotlin-демон падает (`kotlin compile daemon is ready`) — в `gradle.properties` уже `kotlin.compiler.execution.strategy=in-process`.

Если Gradle пишет `kspDebugUnitTestKotlin FAILED` и `lateinit property cleanFilenames has not been initialized` — это **кэш KSP**, не метроном. В `gradle.properties` уже `ksp.incremental=false`. Дальше на Windows:

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

1. **+** → аудио с телефона. Копия: `files/tracks/<id>/original.*` (файл на диске, в плеере не играет).
2. Экран обработки: скачивание модели, чанки по 7.8 с. Можно уйти. Слушать mix, пока считается, нельзя.
3. Когда стемы готовы, открывается плеер: микшер 4 фейдера, Mute/Solo, метроном. Mute вокала — вокал пропадает сразу. Метроном ON — ровный клик на четвертях. Speed = метроном / BPM песни: слайдер 180 при оригинале 120 ускоряет и трек, и клики до 180.
4. Карточка с ошибкой разделения открывает экран обработки, не пустой плеер.

Если скачивание модели сорвалось, положите `htdemucs_fp16weights.onnx` в  
`Android/data/com.rehearsal.app/files/models/`.

## Стек

Kotlin, Jetpack Compose, Media3 ExoPlayer, Room, WorkManager, ONNX Runtime, HT-Demucs.

Вне скоупа: YouTube, облако, iOS, аккорды, git, 6-stem гитара.
