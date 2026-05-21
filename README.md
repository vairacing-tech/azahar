# Azahar Dual Device

Fork de [Azahar](https://github.com/azahar-emu/azahar) para usar dos dispositivos Android como una Nintendo 3DS de dos pantallas:

- Odin 2 Portal: ejecuta Azahar y muestra la pantalla superior.
- Poco F5: ejecuta una app receptora y muestra la pantalla tactil inferior.

El objetivo es jugar en la Odin con la pantalla principal local y enviar la pantalla inferior al movil por red local con baja latencia, manteniendo el tactil del movil como entrada de la pantalla inferior.

## Estado Actual

- Rama de trabajo: `azahar-dual-device`.
- Host Android integrado en Azahar con menu in-game `Dual Device Cast`.
- Receiver Android nativo separado: paquete `dev.azahar.secondscreen`.
- Emparejamiento por QR con esquema `azahar2device://join`.
- Video H.264 por UDP.
- Control/tactil por TCP.
- Encoder y decoder AVC por hardware con preferencia por codecs Qualcomm Snapdragon (`c2.qti.*` / `omx.qcom.*`).
- Sin fallback a encoding software/CPU en el host.

## APKs Debug

Los builds locales se copian en:

```text
artifacts/azahar-host-vanilla-arm64-debug.apk
artifacts/azahar-receiver-debug.apk
```

Instalacion por ADB con los dispositivos usados en este proyecto:

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" -s 2abd611e install -r -d artifacts\azahar-host-vanilla-arm64-debug.apk
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" -s b20085d2 install -r -d -g artifacts\azahar-receiver-debug.apk
```

## Uso

1. Conectar Odin y Poco a la misma Wi-Fi o hotspot local.
2. Abrir Azahar en la Odin y cargar un juego.
3. Activar `Dual Device Cast` desde el menu in-game.
4. Abrir el receiver en el Poco.
5. Elegir resolucion y formato en el receiver.
6. Escanear el QR mostrado por la Odin.
7. Usar el Poco como pantalla inferior tactil.

## Receiver

El receiver permite elegir:

- Resolucion basada en la pantalla tactil 3DS: `320x240`, `640x480`, `960x720`, `1280x960`.
- Aspecto de visualizacion: `4:3`, `16:9`, `Fill`.

La pantalla se mantiene encendida mientras la app esta abierta.

## Build

Desde `src/android`:

```powershell
.\gradlew.bat :receiver:assembleDebug
.\gradlew.bat :app:assembleVanillaDebug "-PazaharAbiFilters=arm64-v8a"
```

Copiar artefactos:

```powershell
Copy-Item .\receiver\build\outputs\apk\debug\receiver-debug.apk ..\..\artifacts\azahar-receiver-debug.apk -Force
Copy-Item .\app\build\outputs\apk\vanilla\debug\app-vanilla-debug.apk ..\..\artifacts\azahar-host-vanilla-arm64-debug.apk -Force
```

## Arquitectura

- Host: `src/android/app/src/main/java/org/citra/citra_emu/dualdevice/DualDeviceCastHost.kt`
- Receiver: `src/android/receiver`
- Plan tecnico: `docs/azahar-dual-device-plan.md`

El host usa el soporte de Azahar para `secondary_window`, `secondarySurfaceChanged()` y eventos tactiles secundarios. Al activar cast, Azahar queda en `SINGLE_SCREEN` para la pantalla superior local y renderiza la pantalla inferior en una `Surface` de encoder H.264.

## Pendientes

- Medir latencia real en Wi-Fi y hotspot local.
- Ajustar bitrate/FPS/resolucion segun rendimiento.
- Validar Vulkan ademas de OpenGL.
- Mejorar reconexion tras perdida de red o cierre del receiver.
- Renombrar namespaces Android heredados de Citra en el host en una pasada dedicada.

## Upstream

Este proyecto parte de Azahar, un emulador open-source de Nintendo 3DS basado en Citra. Para informacion general de Azahar, releases oficiales y requisitos upstream, consultar:

- https://github.com/azahar-emu/azahar
- https://azahar-emu.org/
