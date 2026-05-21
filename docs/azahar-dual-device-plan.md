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

### TV Main Screen Cast

- Host: `TvMainScreenCastHost` dentro del APK Android de Azahar.
- Receiver: web app servida desde el host en `http://<host-ip>:<port>/tv`.
- Emparejamiento: URL local + PIN de 6 digitos.
- Signaling: WebSocket local en `/signal?pin=<pin>`.
- Mensajes JSON: `hello`, `offer`, `answer`, `ice`, `status`, `error`, `stop`.
- Video: WebRTC LAN, H.264 obligatorio, sin STUN/TURN por defecto.
- Layout temporal: `SCREEN_LAYOUT = SINGLE_SCREEN`, `SWAP_SCREEN = true`, `SECONDARY_DISPLAY_LAYOUT = TOP_SCREEN`.
- La TV no envia touch ni controles; toda entrada queda en el host.

## Decisiones Importantes

- Se usa el soporte existente de Azahar para `secondary_window`, `secondarySurfaceChanged()` y eventos tactiles secundarios.
- El host guarda y restaura `SCREEN_LAYOUT`, `SWAP_SCREEN` y `SECONDARY_DISPLAY_LAYOUT` al parar cada modo.
- El encoder del modo Android receiver debe ser hardware/GPU. No se permite fallback a encoder software/CPU; si no hay encoder AVC hardware con entrada `Surface`, el cast falla con error.
- El modo TV usa `SurfaceTextureHelper` + `Surface` del SDK WebRTC Android para alimentar WebRTC sin readback CPU.
- El modo TV limita el receiver web a H.264. Si el navegador no ofrece H.264, se muestra error en lugar de caer a VP8/VP9.
- En hardware Qualcomm disponible se priorizan codecs `c2.qti.*` / `omx.qcom.*` para AVC.
- V1 prioriza LAN/hotspot local. No se implementa TURN ni servidor externo.
- webOS queda como best-effort porque el soporte WebRTC del entorno web puede variar por modelo y version.

## Audio TV

- Modos: `Host`, `TV`, `Both`.
- Default: `Host`.
- Se captura audio desde el pipeline DSP con un tap nativo posterior al volumen del emulador.
- El tap envia PCM16 stereo nativo a Kotlin por JNI.
- Kotlin resamplea a PCM16 stereo 48 kHz en chunks de 10 ms.
- El audio se envia a la TV por WebRTC DataChannel no fiable y no ordenado para evitar cola de latencia.
- El receiver web usa Web Audio API con `AudioWorklet` y fallback a `ScriptProcessorNode`.
- En modo `TV`, la salida local se silencia solo mientras el DataChannel esta abierto; si la TV se desconecta, el audio vuelve al host.

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

- Renombrar namespaces Android heredados en el host. Aunque el paquete instalable ya es `org.azahar_emu.azahar.debug`, buena parte del codigo Kotlin/Java sigue bajo `org.citra.citra_emu` por herencia historica del arbol Android. Hacerlo en una pasada dedicada y comprobar imports, JNI, `NativeLibrary`, manifests, rutas de recursos y scripts Gradle. Mantener el receiver como `dev.azahar.secondscreen`.
- Validar Vulkan ademas de OpenGL para ambos modos.
- Medir latencia real en varias redes locales y ajustar resolucion/bitrate.
- Probar `TV Main Screen Cast` en navegadores de Smart TV concretos.
