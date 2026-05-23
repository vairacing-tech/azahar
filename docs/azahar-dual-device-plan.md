# Azahar Dual Device y TV Cast

## Objetivo

Usar Azahar como base para dos modos de pantalla externa en red local:

- `Dual Device Cast`: un Android host ejecuta Azahar y muestra la pantalla superior; otro Android muestra la pantalla inferior tactil.
- `TV Main Screen Cast`: un Android host ejecuta Azahar, mantiene controles y pantalla inferior tactil local; una TV o navegador compatible muestra la pantalla superior por WebRTC.

## Arquitectura

### Dual Device Cast

- Host: fork Android de Azahar.
- Receptor: app Android nativa separada, paquete `dev.azahar.secondscreen`.
- Emparejamiento: QR con URI `azahar2device://join?host=<ip>&control=<port>&video=<port>&token=<token>&screen=bottom`.
- Control: TCP local con token de sesion, mensajes `HELLO`, `TOUCH`, `PING`, `STOP`.
- Video: H.264 por UDP con fragmentacion propia y descarte de frames antiguos en el receptor.
- Layout temporal: `SCREEN_LAYOUT = SINGLE_SCREEN`, `SECONDARY_DISPLAY_LAYOUT = BOTTOM_SCREEN`.
- Puede iniciarse antes de arrancar el juego desde la ficha del juego con `Second screen`. El host espera a que el receiver escanee el QR, crea el encoder y la `Surface`, y entonces habilita `Start game`. Esta es la ruta recomendada para Vulkan.

### TV Main Screen Cast

- Host: `TvMainScreenCastHost` dentro del APK Android de Azahar.
- Receiver: web app servida desde el host en `http://<host-ip>:<port>/tv`.
- Emparejamiento: URL local + PIN de 4 digitos.
- Signaling: WebSocket local en `/signal?pin=<pin>`.
- Mensajes JSON: `hello`, `offer`, `answer`, `ice`, `status`, `error`, `stop`.
- Video: WebRTC LAN, H.264 obligatorio, sin STUN/TURN por defecto.
- Resolucion configurable en el host por altura: `240p`, `480p`, `720p`, `960p`, `1080p`.
- Aspecto configurable en el host: `Native 5:3`, `16:9`, `Fill`. En `Native 5:3`, 1080p emite `1800x1080`; en `16:9` y `Fill`, 1080p emite `1920x1080`.
- Bitrate configurable en el host: `Auto`, `Low 4 Mbps`, `Medium 8 Mbps`, `High 14 Mbps`.
- Layout temporal: `SCREEN_LAYOUT = SINGLE_SCREEN`, `SWAP_SCREEN = true`, `SECONDARY_DISPLAY_LAYOUT = TOP_SCREEN`.
- La TV no envia touch ni controles; toda entrada queda en el host.
- Puede iniciarse antes de arrancar el juego desde la ficha del juego con `Play on TV`. Esta ruta crea la `Surface` secundaria antes de que el renderer arranque y es la ruta recomendada para Vulkan.

## Decisiones Importantes

- Se usa el soporte existente de Azahar para `secondary_window`, `secondarySurfaceChanged()` y eventos tactiles secundarios.
- El host guarda y restaura `SCREEN_LAYOUT`, `SWAP_SCREEN` y `SECONDARY_DISPLAY_LAYOUT` al parar cada modo.
- El encoder del modo Android receiver debe ser hardware/GPU. No se permite fallback a encoder software/CPU; si no hay encoder AVC hardware con entrada `Surface`, el cast falla con error.
- El modo TV usa `SurfaceTextureHelper` + `Surface` del SDK WebRTC Android para alimentar WebRTC sin readback CPU.
- Los modos de cast se pueden activar en caliente durante una partida solo con OpenGL ES. Con Vulkan se usa `Second screen` o `Play on TV` antes de arrancar el juego para que la `Surface` secundaria exista antes de crear el renderer. Se evita el cambio caliente de `ANativeWindow`, que fallo en Android con `dequeueBuffer failed: No such device` y puede reiniciar SurfaceFlinger.
- El modo TV limita el receiver web a H.264. Si el navegador no ofrece H.264, se muestra error en lugar de caer a VP8/VP9.
- H.265/HEVC queda como ruta futura experimental. No se expone en la UI v1 porque WebRTC H.265 no esta garantizado en navegadores de Smart TV y no conviene ofrecer un modo que caiga a software o falle silenciosamente.
- En hardware Qualcomm disponible se priorizan codecs `c2.qti.*` / `omx.qcom.*` para AVC.
- El modo TV usa `HardwareVideoEncoderFactory` con politica H.264 hardware-only. Si hay encoder Qualcomm, la politica restringe WebRTC a Qualcomm; si no, permite otros encoders H.264 hardware. No usa encoder software.
- V1 prioriza LAN/hotspot local. No se implementa TURN ni servidor externo.
- webOS queda como best-effort porque el soporte WebRTC del entorno web puede variar por modelo y version.

## Audio TV

- Modos: `Host`, `TV`, `Both`.
- Default: `Host`.
- Codecs seleccionables:
  - `PCM low latency`: PCM16 stereo 48 kHz por WebRTC DataChannel no fiable/no ordenado.
  - `Opus low bandwidth`: audio track WebRTC con Opus; el host alimenta el modulo de audio WebRTC con PCM16 stereo 48 kHz desde el tap DSP.
- Se captura audio desde el pipeline DSP con un tap nativo posterior al volumen del emulador.
- El tap envia PCM16 stereo nativo a Kotlin por JNI.
- Kotlin resamplea a PCM16 stereo 48 kHz en chunks de 10 ms.
- El receiver web usa Web Audio API con `AudioWorklet` y fallback a `ScriptProcessorNode` para PCM, y un elemento audio para el track Opus.
- En modo `TV`, la salida local se silencia solo mientras hay una ruta de audio activa hacia la TV; si la TV se desconecta, el audio vuelve al host.

## Pruebas

- `.\gradlew.bat :receiver:assembleDebug`
- `.\gradlew.bat :app:compileVanillaDebugKotlin "-PazaharAbiFilters=arm64-v8a"`
- `.\gradlew.bat :app:assembleVanillaDebug "-PazaharAbiFilters=arm64-v8a"`

## Estado Actual

- Host Kotlin `DualDeviceCastHost` implementado.
- Host Kotlin `TvMainScreenCastHost` implementado.
- Menu in-game incluye `Dual Device Cast` y `TV Main Screen Cast`.
- Receptor Android nativo agregado como modulo Gradle `:receiver`.
- Receiver web servido desde el host Android.
- Audio TV configurable mediante tap nativo + DataChannel PCM.

## Deuda Tecnica

- Renombrar namespaces Android heredados en el host. Aunque el paquete instalable del fork ya es `org.azahar2s.azahar.debug`, buena parte del codigo Kotlin/Java sigue bajo `org.citra.citra_emu` por herencia historica del arbol Android. Hacerlo en una pasada dedicada y comprobar imports, JNI, `NativeLibrary`, manifests, rutas de recursos y scripts Gradle. Mantener el receiver como `dev.azahar.secondscreen`.
- Validar Vulkan ademas de OpenGL para ambos modos, especialmente las rutas pre-game `Second screen` y `Play on TV`.
- Investigar H.265/HEVC con deteccion explicita de soporte en host y receiver, sin fallback software.
- Medir latencia real en varias redes locales y ajustar resolucion/bitrate.
- Probar `TV Main Screen Cast` en navegadores de Smart TV concretos.
