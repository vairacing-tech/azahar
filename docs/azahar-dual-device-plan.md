# Azahar Segunda Pantalla Android

## Objetivo

Usar Azahar como base para que una Odin 2 Portal ejecute la emulacion de Nintendo 3DS y un Poco F5 funcione como segunda pantalla tactil. La Odin muestra la pantalla superior y el Poco recibe la pantalla inferior por red local.

## Arquitectura

- Host: fork Android de Azahar.
- Receptor: app Android nativa separada, paquete `dev.citra2device.receiver`.
- Emparejamiento: QR con URI `azahar2device://join?host=<ip>&control=<port>&video=<port>&token=<token>&screen=bottom`.
- Control: TCP local con token de sesion, mensajes `HELLO`, `TOUCH`, `PING`, `STOP`.
- Video: H.264 por UDP con fragmentacion propia y descarte de frames antiguos en el receptor.

## Decisiones Importantes

- Se usa el soporte existente de Azahar para `secondary_window`, `secondarySurfaceChanged()` y `onSecondaryTouchEvent()`.
- El host configura el layout local como `SINGLE_SCREEN` y el layout secundario como `BOTTOM_SCREEN` mientras el cast esta activo, restaurando la configuracion previa al parar.
- El encoder debe ser hardware/GPU. No se permite fallback a encoder software/CPU; si no hay encoder AVC hardware con entrada `Surface`, el cast falla con error.
- La entrada al encoder usa `MediaCodec.createInputSurface()`, evitando readback CPU de frames.
- V1 prioriza OpenGL; Vulkan queda para validacion posterior porque Azahar soporta ambos backends.

## Pruebas

- `.\gradlew.bat :receiver:assembleDebug`
- `.\gradlew.bat :app:compileVanillaDebugKotlin`
- `.\gradlew.bat :app:assembleVanillaDebug` requiere submodulos nativos de Azahar inicializados con `git submodule update --init --recursive`.

## Estado Actual

- Host Kotlin implementado en `DualDeviceCastHost`.
- Menu in-game de Azahar incluye `Dual Device Cast`.
- Receptor Android nativo agregado como modulo Gradle `:receiver`.
- Verificado: receptor genera APK debug y Kotlin del host compila.
- Pendiente: build nativo completo de Azahar y prueba real Odin/Poco con juego.
