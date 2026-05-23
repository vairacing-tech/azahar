# Azahar Dual Device

Fork de [Azahar](https://github.com/azahar-emu/azahar) para usar pantallas externas en red local con baja latencia.

Modos incluidos:

- `Dual Device Cast`: Android host ejecuta Azahar y muestra la pantalla superior; otro Android muestra la pantalla inferior tactil.
- `TV Main Screen Cast`: Android host ejecuta Azahar, mantiene controles y pantalla inferior tactil local; una TV o navegador compatible muestra la pantalla superior por WebRTC.

## Estado Actual

- Rama de trabajo: `azahar-dual-device`.
- Host Android integrado en Azahar con menu in-game `Dual Device Cast`.
- Host Android integrado en Azahar con menu in-game `TV Main Screen Cast`.
- Receiver Android nativo separado: paquete `dev.azahar.secondscreen`.
- Emparejamiento Android receiver por QR con esquema `azahar2device://join`.
- Emparejamiento TV por URL local y boton `Connect`.
- Video Android receiver: H.264 por UDP.
- Video TV: WebRTC LAN, H.264 y encoder hardware mediante el SDK WebRTC Android.
- Control/tactil Android receiver por TCP.
- Audio TV configurable: `Host`, `TV`, `Both`.
- Audio TV seleccionable: PCM por DataChannel para menor complejidad/latencia o Opus como audio track WebRTC para menor ancho de banda.
- Resolucion de `TV Main Screen Cast` configurable desde 240p hasta 1080p, aspecto `Native 5:3`, `16:9` o `Fill`, y bitrate configurable desde el host.
- `Dual Device Cast` tambien puede iniciarse antes del juego con `Second screen` desde la ficha del juego. Esta ruta prepara el encoder y la surface secundaria antes de arrancar el renderer.
- `TV Main Screen Cast` puede iniciarse antes del juego con `Play on TV` desde la ficha del juego. Esta es la ruta recomendada para Vulkan porque crea la surface secundaria antes de arrancar el renderer.
- La activacion en caliente de los modos de cast durante una partida queda limitada a OpenGL ES; con Vulkan hay que usar `Second screen` o `Play on TV` antes de arrancar el juego.
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
4. Elegir resolucion y formato en el receiver.
5. Escanear el QR mostrado por el host.
6. Si se uso `Second screen`, pulsar `Start game` cuando el host indique que el receiver esta conectado.
7. Usar el receiver como pantalla inferior tactil.

### TV Main Screen

1. Conectar el Android host y la TV o navegador receptor a la misma Wi-Fi o hotspot local.
2. Para Vulkan, mantener pulsado un juego y elegir `Play on TV`; para OpenGL tambien se puede activar `TV Main Screen Cast` desde el menu in-game.
3. Elegir resolucion, aspecto, bitrate, salida de audio y codec de audio en el dialog del host.
4. En la TV, abrir la URL local mostrada por el host.
5. Pulsar `Connect` en la TV o navegador receptor.
6. Si se uso `Play on TV`, pulsar `Start game` cuando el pairing este listo.
7. El host queda con controles y pantalla inferior tactil local; la TV muestra la pantalla superior.

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

- Host: `DualDeviceCastHost.kt`
- Host TV: `TvMainScreenCastHost.kt`
- Receiver: `src/android/receiver`
- Plan tecnico: `docs/azahar-dual-device-plan.md`

El host usa el soporte de Azahar para `secondary_window`, `secondarySurfaceChanged()` y eventos tactiles secundarios. Al activar cast, Azahar queda en `SINGLE_SCREEN` para la pantalla superior local y renderiza la pantalla inferior en una `Surface` de encoder H.264.

En `TV Main Screen Cast`, Azahar queda temporalmente en `SINGLE_SCREEN` con `SWAP_SCREEN = true`, de forma que el host muestra la pantalla inferior local y la `Surface` secundaria recibe la pantalla superior. Esa `Surface` alimenta WebRTC mediante `SurfaceTextureHelper`, evitando readback CPU. El encoder WebRTC se limita a H.264 hardware y prioriza Qualcomm cuando hay encoder Snapdragon disponible.

Con Vulkan, la ruta estable es iniciar desde `Second screen` o `Play on TV` antes del juego. El cambio en caliente de la `Surface` secundaria no se usa con Vulkan porque en Android puede invalidar la swapchain o el compositor. H.265/HEVC queda planteado como mejora futura con deteccion explicita de soporte en host y TV, sin fallback a software.

## Pendientes

- Medir latencia real en Wi-Fi y hotspot local.
- Ajustar bitrate/FPS/resolucion segun rendimiento.
- Validar las rutas `Second screen` y `Play on TV` con Vulkan en mas dispositivos.
- Investigar H.265/HEVC para TV cast cuando host y navegador receptor lo soporten por hardware.
- Validar `TV Main Screen Cast` en navegadores de Smart TV concretos; webOS queda como best-effort.
- Mejorar reconexion tras perdida de red o cierre del receiver.
- Renombrar namespaces Android heredados en el host en una pasada dedicada.

## Upstream

Este proyecto parte de Azahar, un emulador open-source de Nintendo 3DS. Para informacion general de Azahar, releases oficiales y requisitos upstream, consultar:

- https://github.com/azahar-emu/azahar
- https://azahar-emu.org/
