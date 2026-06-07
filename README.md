# Azahar Dual Device

Fork de [Azahar](https://github.com/azahar-emu/azahar) para usar pantallas externas en red local con baja latencia.

Modo incluido:

- `Dual Device Cast`: Android host ejecuta Azahar; otro Android muestra la pantalla tactil inferior o la pantalla principal superior.

## Estado Actual

- Rama de trabajo: `azahar-dual-device`.
- Host Android integrado en Azahar con menu in-game `Dual Device Cast`.
- Receiver Android nativo separado: paquete `dev.azahar.secondscreen`.
- Emparejamiento Android receiver por QR con esquema `azahar2device://join`.
- Video Android receiver: AVC/H.264 o HEVC/H.265 por UDP.
- Control/tactil Android receiver por TCP.
- El receiver Android permite elegir pantalla, resolucion, aspecto, codec, bitrate y 30/60 Hz.
- `Dual Device Cast` tambien puede iniciarse antes del juego con `Second screen` desde la ficha del juego. Esta ruta prepara el encoder y la surface secundaria antes de arrancar el renderer.
- La activacion en caliente del cast durante una partida queda limitada a OpenGL ES; con Vulkan hay que usar `Second screen` antes de arrancar el juego.
- Sin fallback a encoding software/CPU en los modos de cast del host.

## APKs Debug

Los builds locales se copian en:

```text
artifacts/azahar-host-vanilla-arm64-debug.apk
artifacts/azahar-receiver-debug.apk
```

Instalacion por ADB:

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" -s <host-serial> install -r -d artifacts\azahar-host-vanilla-arm64-debug.apk
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" -s <receiver-serial> install -r -d -g artifacts\azahar-receiver-debug.apk
```

## Uso

### Android Receiver

1. Conectar el Android host y el Android receiver a la misma Wi-Fi o hotspot local.
2. Para Vulkan, mantener pulsado un juego y elegir `Second screen`; para OpenGL tambien se puede activar `Dual Device Cast` desde el menu in-game.
3. Abrir la app receiver en el segundo dispositivo Android.
4. Elegir pantalla, resolucion, codec, bitrate, refresco y formato en el receiver.
5. Escanear el QR mostrado por el host.
6. Si se uso `Second screen`, pulsar `Start game` cuando el host indique que el receiver esta conectado.
7. Usar el receiver como pantalla externa. El touch remoto solo se envia cuando se streamea la pantalla tactil.

## Receiver

El receiver permite elegir:

- Pantalla seleccionable: tactil inferior o principal superior.
- Resolucion basada en la pantalla elegida: `1x`, `2x`, `3x`, `4x`.
- Codec seleccionable: `AVC/H.264` o `HEVC/H.265`.
- Bitrate seleccionable: `Auto`, `2`, `4`, `8`, `12`, `20 Mbps`.
- Refresco seleccionable: `30 Hz` o `60 Hz`.
- Aspecto de visualizacion: `Native`, `16:9`, `Fill`.

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

- Host: `DualDeviceCastHost.kt`
- Receiver: `src/android/receiver`
- Plan tecnico: `docs/azahar-dual-device-plan.md`

El host usa el soporte de Azahar para `secondary_window`, `secondarySurfaceChanged()` y eventos tactiles secundarios. Al activar cast, Azahar queda en `SINGLE_SCREEN` y renderiza la pantalla elegida en una `Surface` de encoder AVC o HEVC. Si se streamea la pantalla tactil, el receptor Android envia eventos tactiles; si se streamea la principal, el touch remoto queda desactivado.

Con Vulkan, la ruta estable es iniciar desde `Second screen` antes del juego. El cambio en caliente de la `Surface` secundaria no se usa con Vulkan porque en Android puede invalidar la swapchain o el compositor.

## Pendientes

- Medir latencia real en Wi-Fi y hotspot local.
- Ajustar bitrate/FPS/resolucion segun rendimiento.
- Validar la ruta `Second screen` con Vulkan en mas dispositivos.
- Mejorar reconexion tras perdida de red o cierre del receiver.
- Renombrar namespaces Android heredados en el host en una pasada dedicada.

## Upstream

Este proyecto parte de Azahar, un emulador open-source de Nintendo 3DS. Para informacion general de Azahar, releases oficiales y requisitos upstream, consultar:

- https://github.com/azahar-emu/azahar
- https://azahar-emu.org/
