# Azahar Dual Device Cast

## Objetivo

Usar Azahar como base para pantalla externa en red local entre dos dispositivos Android:

- `Dual Device Cast`: un Android host ejecuta Azahar; otro Android muestra la pantalla tactil inferior o la pantalla principal superior segun la seleccion del receptor.

## Arquitectura

### Dual Device Cast

- Host: fork Android de Azahar.
- Receptor: app Android nativa separada, paquete `dev.azahar.secondscreen`.
- Emparejamiento: QR con URI `azahar2device://join?host=<ip>&control=<port>&video=<port>&token=<token>`.
- Control: TCP local con token de sesion, mensajes `HELLO`, `TOUCH`, `PING`, `STOP`.
- Video: AVC/H.264 o HEVC/H.265 por UDP con fragmentacion propia y descarte de frames antiguos en el receptor.
- Opciones del receptor: pantalla (`Touch screen` o `Main screen`), resolucion `1x` a `4x`, codec, `30 Hz` o `60 Hz`, bitrate `Auto` o fijo, y aspecto `Native`, `16:9`, `Fill`.
- Layout temporal:
  - Pantalla tactil: `SCREEN_LAYOUT = SINGLE_SCREEN`, `SWAP_SCREEN = false`, `SECONDARY_DISPLAY_LAYOUT = BOTTOM_SCREEN`.
  - Pantalla principal: `SCREEN_LAYOUT = SINGLE_SCREEN`, `SWAP_SCREEN = true`, `SECONDARY_DISPLAY_LAYOUT = TOP_SCREEN`.
- Puede iniciarse antes de arrancar el juego desde la ficha del juego con `Second screen`. El host espera a que el receiver escanee el QR, crea el encoder y la `Surface`, y entonces habilita `Start game`. Esta es la ruta recomendada para Vulkan.

## Decisiones Importantes

- Se usa el soporte existente de Azahar para `secondary_window`, `secondarySurfaceChanged()` y eventos tactiles secundarios.
- El host guarda y restaura `SCREEN_LAYOUT`, `SWAP_SCREEN`, `ASPECT_RATIO` y `SECONDARY_DISPLAY_LAYOUT` al parar el cast.
- El encoder debe ser hardware/GPU con entrada `Surface`. No se permite fallback a encoder software/CPU; si no hay encoder hardware para el codec elegido, el cast falla con error.
- El cast se puede activar en caliente durante una partida solo con OpenGL ES. Con Vulkan se usa `Second screen` antes de arrancar el juego para que la `Surface` secundaria exista antes de crear el renderer. Se evita el cambio caliente de `ANativeWindow`, que fallo en Android con `dequeueBuffer failed: No such device` y puede reiniciar SurfaceFlinger.
- En hardware Qualcomm disponible se priorizan codecs `c2.qti.*` / `omx.qcom.*` para AVC/HEVC.
- V1 prioriza LAN/hotspot local. No se implementa TURN ni servidor externo.

## Pruebas

- `.\gradlew.bat :receiver:assembleDebug`
- `.\gradlew.bat :app:compileVanillaDebugKotlin "-PazaharAbiFilters=arm64-v8a"`
- `.\gradlew.bat :app:assembleVanillaDebug "-PazaharAbiFilters=arm64-v8a"`

## Estado Actual

- Host Kotlin `DualDeviceCastHost` implementado.
- Menu in-game incluye `Dual Device Cast`.
- Receptor Android nativo agregado como modulo Gradle `:receiver`.
- Receiver Android permite elegir pantalla, codec AVC/HEVC, bitrate y refresco 30/60 Hz.

## Deuda Tecnica

- Renombrar namespaces Android heredados en el host. Aunque el paquete instalable del fork ya es `org.azahar2s.azahar.debug`, buena parte del codigo Kotlin/Java sigue bajo `org.citra.citra_emu` por herencia historica del arbol Android. Hacerlo en una pasada dedicada y comprobar imports, JNI, `NativeLibrary`, manifests, rutas de recursos y scripts Gradle. Mantener el receiver como `dev.azahar.secondscreen`.
- Validar Vulkan ademas de OpenGL, especialmente la ruta pre-game `Second screen`.
- Medir latencia real en varias redes locales y ajustar resolucion/bitrate.
