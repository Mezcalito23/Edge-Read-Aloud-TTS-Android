# Guía definitiva de implementación — versión verificada

## Edge-Read-Aloud-TTS-Android

Esta versión consolida y vuelve a contrastar `Guia-de-implementacion-2.docx`, `FULL.docx`, `Informe-final-3.docx` y la guía anterior. Corrige ambigüedades de alcance, cifras, criterios de aceptación y decisiones de arquitectura. Las cuatro fases son el mínimo práctico: menos fases mezclaría cambios de distinto riesgo; más fases fragmentaría tareas que deben validarse juntas.

## 1. Decisiones definitivas

### Principios

- Mantener `TextToSpeechService`, XML Views, `ExecutorService`/`Handler`, OkHttp, DataStore y `MediaExtractor`/`MediaCodec`.
- No introducir Compose, Hilt, Room, ExoPlayer, fallback offline ni una migración completa a coroutines como parte de esta implementación.
- Tratar Edge como un protocolo externo sujeto a 403, 429, EOF, timeouts, cambios de cabeceras y cambios de formato.
- Garantizar una terminación única por solicitud: `done()` o `error()`/cancelación compatible con la API mínima, nunca retornos silenciosos.
- Prohibir I/O bloqueante en UI, callbacks Binder, callbacks TTS y lector WebSocket.
- Priorizar el comportamiento del código sobre comentarios y README. Una afirmación se considera implementada solo cuando existe código y una prueba que la respalda.

### Integrar obligatoriamente

1. Terminalidad de callback en todos los caminos, incluido fallo de `deliver()` y cancelación.
2. Catálogo con DRM, cabeceras, MUID, UA configurado, validación de respuesta y reintento acotado tras 403.
3. Eliminación de reflexión para `android.speech.tts.Voice`.
4. Sanitización de controles no válidos para XML 1.0/Edge TTS.
5. Segmentación UTF-8 con límite en bytes, pendiente de confirmar mediante pruebas el límite operativo exacto.
6. Cancelación sin carrera, propagada a WebSocket, espera, decoder y cualquier prefetch.
7. Deriva de reloj atómica, basada en ajuste absoluto, con validación de `Date` y límite máximo.
8. Validación de User-Agent y Origin antes de persistirlos y fallback seguro ante configuraciones heredadas inválidas.
9. Memoización e índices inmutables del catálogo.
10. Escrituras asíncronas de DataStore y snapshot de configuración en memoria.
11. Diagnóstico acumulado en memoria y persistido una sola vez, asíncronamente y sin secretos.
12. Escrituras atómicas de catálogo y caché.
13. Caché MP3, no PCM, con nueva versión de formato/protocolo.
14. Watchdog de inactividad, con un límite total superior solo como protección adicional.
15. Mapas compartidos ISO2/ISO3.
16. Cliente OkHttp compartido.
17. Decoder que falla pronto ante entrada inválida o falta de progreso.
18. Tests JVM deterministas; los tests instrumentados no deben depender de red real por defecto.
19. R8/minify y lint endurecidos después de validar reglas y release.
20. Correcciones de manifest, locale config, Direct Boot, errores localizados, código muerto y README.

### Posponer o no integrar por defecto

- **Content-Type estrictamente bloqueante:** registrar cualquier valor inesperado y rechazar solo cuando el frame sea inequívocamente inválido o el decoder no pueda aceptarlo. El diagnóstico no puede ser una excusa para reproducir datos no verificables como MP3.
- **Word boundaries:** no activarlos por defecto porque `SynthesisCallback` no ofrece una API para exponerlos al cliente. Si se necesita metadata interna, debe activarse mediante una opción diagnóstica y probarse con fixtures.
- **WebSocket persistente:** implementarlo después de los contratos y la cancelación, nunca como primer parche. Requiere correlación estricta de turnos, invalidación por configuración y recuperación ante cierre.
- **Streaming MP3→PCM:** construirlo como subfase condicionada. `MediaExtractor` con entrada incremental no se considera válido hasta probarlo en las APIs/dispositivos mínimos.
- **Prefetch:** solo después de un scheduler cancelable, límites de memoria y pruebas que demuestren que `onStop()` cancela tráfico pendiente.

## 2. Riesgos prioritarios y recomendaciones

### Seis riesgos iniciales

| Riesgo | Estado | Decisión |
|---|---|---|
| Complejidad concentrada en servicio/actividad | Sigue vigente | Extraer responsabilidades gradualmente al final de la fase 1/2; no reescribir toda la UI |
| Dependencia externa del protocolo Edge | Sigue vigente | DRM común, catálogo robusto, deriva, watchdog, reconexión y diagnóstico |
| Seguridad y privacidad de texto/caché | Parcial | Mantener hash; migrar a MP3 atómico; validar configuración; redactar logs; no persistir texto/audio sensible |
| Concurrencia y cancelación | Sigue vigente | Handle publicable antes de conectar, cancelación propagada y guardia terminal única |
| Pruebas insuficientes | Sigue vigente | Tests JVM y fixtures antes de cambios arquitectónicos |
| Mantenimiento del protocolo | Parcial | Constantes, DRM común, parser testeado, fixtures y metadata explícitamente gestionada |

### Ocho recomendaciones concretas

| Recomendación | Estado | Decisión |
|---|---|---|
| Extraer orquestador de síntesis | No integrada | Aplicar gradualmente cuando los contratos estén cubiertos |
| Máquina de estados de síntesis | Parcial | Formalizar estados internos o `sealed class`; evitar una reescritura prematura |
| Tests exhaustivos de `TextSegmenter` | No integrada | Obligatoria en fase 1 |
| Tests y contrato del audio | Parcial | Fixtures de parser y decoder; tests de corrupción, EOS y cancelación |
| Política de caché | Parcial | MP3, versión, claves no ambiguas, atomicidad, LRU y limpieza |
| Observabilidad sin texto sensible | Parcial | Buffer en memoria, persistencia única/asíncrona, redacción y límites |
| Separar UI/estado con ViewModel | No imprescindible | Posponer; dividir helpers/Controller sin introducir Compose |
| Documentar límites y responsabilidad | Parcial | Actualizar README solo con capacidades comprobadas |

## 3. Fases mínimas

# Fase 1 — Contratos, entradas y pruebas

## Objetivo

Eliminar fallos deterministas, fijar contratos de texto/audio/callback y crear pruebas reproducibles sin Microsoft ni dispositivo físico.

## Archivos

- `Models.kt`
- `TextSegmenter.kt`
- `SsmlBuilder.kt`
- `AudioFrameParser.kt`
- `EdgeReadAloudTtsService.kt`
- nuevo `TextSanitizer.kt`
- `app/src/test/java/dev/experimental/edgetts/`
- `app/build.gradle.kts`

## Sanitización y SSML

Implementar `TextSanitizer.removeIncompatibleCharacters()` reemplazando por espacio U+0000–U+0008, U+000B–U+000C y U+000E–U+001F. Preservar U+0009, U+000A y U+000D. Mantener separada la operación opcional de normalización de espacios: colapsar espacios cambia el texto y no debe ejecutarse implícitamente sin documentarlo.

Sanitizar antes de segmentar y antes de construir SSML. `escapeXml()` debe escapar `&`, `<`, `>`, comillas y apóstrofes, y debe tratar surrogates sueltos y U+FFFE/U+FFFF de modo seguro. Sanitización de controles y escape XML son capas distintas.

## Segmentación UTF-8

Usar un límite de bytes UTF-8. `4096` es el valor de referencia de `rany2/edge-tts v7.2.8`, no una verdad inmutable: debe confirmarse contra el protocolo y las pruebas del repositorio antes de fijarse como contrato. El algoritmo debe:

1. Convertir a UTF-8.
2. No superar el máximo configurado.
3. Preferir párrafos, saltos de línea y espacios; usar corte duro como último recurso.
4. Nunca cortar secuencias UTF-8 ni producir caracteres de reemplazo.
5. Preservar todo el contenido no descartado y evitar bucles si el punto elegido no avanza.
6. Consultar cancelación en cada iteración.

La API pública `segment(text)` y `segment(text, isCancelled)` debe conservarse si no existe una incompatibilidad real.

## Contrato de audio y callback

Renombrar `onPcmChunk` a `onEncodedAudioChunk` —o `onAudioChunk` si se documenta claramente— porque el WebSocket entrega MP3 y no PCM. El decoder recibe MP3; `SynthesisCallback` recibe PCM.

En `synthesizeInternal()`:

- si `deliver()` devuelve `false`, llamar a `guard.error()` una sola vez;
- ante cancelación, usar la ruta terminal compatible con la API mínima (`callback.error(int)` cuando corresponda);
- no retornar silenciosamente antes de `start()` o después de iniciarlo;
- mantener una autoridad única (`TerminalGuard`) para impedir `done()`/`error()` dobles.

## Tests JVM mínimos

Crear tests para:

- texto vacío, espacios y solo controles;
- ASCII en el límite exacto;
- CJK, árabe, hebreo, hindi, ruso, emojis y texto mixto;
- 1000 caracteres chinos dentro del límite y texto mixto que lo exceda;
- 1366 caracteres chinos, que fuerzan un corte cercano al límite sin romper UTF-8;
- palabra/token individual mayor que el límite;
- cancelación durante la segmentación;
- preservación de contenido al concatenar segmentos, definiendo explícitamente el tratamiento de espacios y saltos;
- U+0000–U+0008, U+000B–U+000C y U+000E–U+001F;
- preservación de tab, LF y CR;
- texto limpio y Unicode con acentos, CJK y árabe sin modificaciones;
- escape XML y caracteres inválidos;
- frames textuales/binarios, prefijo big-endian, CRLF, payload vacío, Content-Type válido/inesperado y datos truncados;
- decoder ante entrada vacía/corrupta, EOS, no progreso y liberación de recursos;
- terminal exactly-once usando callback falso.

Ningún test JVM contactará la red real. Sustituir reflexión de `setWsUrlForTest()` y del almacenamiento por dependencias inyectables o APIs limitadas a tests.

## Salida de fase

`./gradlew test` debe ejecutar una suite JVM verde y reproducible. No avanzar si segmentación, sanitización, SSML, parser, decoder básico y terminalidad no están cubiertos.

# Fase 2 — Red, catálogo y cancelación

## DRM común y catálogo

Crear un componente pequeño e inyectable para TrustedClientToken, `Sec-MS-GEC`, versión, FILETIME con sufijo `Z`, MUID, cabeceras y deriva. WebSocket y catálogo deben reutilizarlo; no duplicar token en URL y constante.

En `VoiceCatalogRepository.refresh()`:

- usar `snap.userAgent` validado;
- enviar cabeceras requeridas, GEC, versión y Cookie MUID;
- reintentar una vez tras 403;
- comprobar código HTTP, cuerpo no vacío y JSON válido;
- reemplazar el archivo solo después de validar todo;
- escribir temporal y renombrar en el mismo directorio;
- actualizar `catalogUpdatedAt` solo después del commit.

Para la corrección de reloj, aceptar `Date` solo si se puede parsear y el valor es razonable. Calcular el ajuste absoluto respecto al reloj local; descartar diferencias absurdas, por ejemplo mayores de 24 horas. Usar un valor atómico en segundos/milisegundos, no `Double +=`. Los tests deben inyectar reloj, servidor y estado reiniciable.

En `cached()`:

- memoizar lista inmutable con invalidación por `lastModified()` y `refresh()`;
- precalcular mapas por `shortName`, ISO2/ISO3 y locale;
- exponer `CATALOG_FILENAME` y reutilizarlo en `CheckVoiceData`;
- conservar el catálogo anterior si el nuevo es inválido.

## Watchdog y cancelación

El watchdog debe reprogramarse con cada frame válido y distinguir:

- timeout de conexión;
- timeout de primer byte;
- timeout de inactividad;
- límite total de seguridad.

Publicar el handle cancelable antes de abrir WebSocket. `onStop()` debe marcar cancelación, cancelar socket, despertar latch/cola, interrumpir espera, decoder y prefetch. Usar lock o compare-and-set para no cancelar una solicitud nueva.

## DataStore, UI y diagnóstico

Mantener un snapshot `@Volatile` actualizado mediante el flujo de DataStore en un hilo propio. Callbacks Binder consultan memoria y no `snapshotBlocking()` en cada llamada. Los setters encolan persistencia I/O; la UI actualiza su estado inmediatamente.

El slider solo muestra preview en `onProgressChanged` y persiste una vez en `onStopTrackingTouch`. `UiLanguage` no debe bloquear `attachBaseContext` en cada recreación.

Acumular diagnóstico en memoria; persistir una sola vez al completar/fallar y fuera del hilo OkHttp. Redactar token, MUID, cookies, SSML, texto, audio, query sensible y payloads. El logging de `Content-Type` debe incluir tipo, tamaño, segmento/sesión y acción tomada, sin contenido del usuario.

## Locales

Extraer `LocaleCodes` con mapas lazy ISO3↔ISO2 y reutilizarlo en servicio, `CheckVoiceData` y `GetSampleText`.

## Salida de fase

Probar respuestas 200/403/429/500, JSON inválido, `Date` ausente/incorrecta/absurda, cierre anormal, cancelación antes de conectar, cancelación durante frame y timeout de inactividad. Confirmar ausencia de I/O síncrono en UI, Binder y lector WebSocket.

# Fase 3 — Caché, decoder y rendimiento

## Caché MP3

Migrar PCM a MP3:

- `readMp3()` valida entrada y elimina archivo inválido;
- `writeMp3()` escribe temporal + flush/rename;
- nueva versión, por ejemplo `edge-readaloud-v2-mp3`;
- clave estructurada sin colisiones por separadores ambiguos;
- incluir texto sanitizado, voz, locale efectivo, rate, pitch, formato y versión;
- mantener LRU/TTL/límite en bytes;
- no registrar ni derivar nombres legibles del contenido.

La ventaja teórica debe describirse correctamente: PCM mono 24 kHz/16-bit equivale aproximadamente a 384 kbps; MP3 de referencia de 48 kbps equivale a una reducción aproximada de **8×** en almacenamiento, no 16×, salvo que se compare con otra tasa MP3 documentada. No prometer horas exactas sin medir el bitrate real recibido.

Decodificar MP3 al leer de caché. Invalidar `.pcm` antiguos o migrarlos con una política explícita.

## Atomicidad y decoder

Para catálogo y caché: temporal en el mismo directorio, cierre, sincronización cuando corresponda, rename/replacement atómico, limpieza de temporales y conservación del archivo anterior ante error. Un tamaño PCM par no prueba integridad; preferir validación de MP3/header o metadata de tamaño/checksum.

El decoder debe rechazar entrada vacía, limitar bytes y deadline, abortar tras polls sin progreso después de EOS, liberar recursos siempre y distinguir audio ausente de entrada corrupta.

## Segmentos y streaming

4096 bytes es el límite de protocolo de referencia; el objetivo operativo puede ser menor —por ejemplo 600–1000 caracteres o un máximo de bytes medido— para mejorar primer audio y reducir timeout. Elegirlo mediante métricas multilingües, no arbitrariamente.

El streaming es una subfase condicionada. Antes de cambiar el servicio, definir una interfaz incremental y probar `MediaCodec` en las APIs/dispositivos mínimos. Debe ofrecer back-pressure, bloques PCM compatibles con `SynthesisCallback`, cancelación inmediata y cero acumulación simultánea de MP3+PCM completos. Si no es fiable, conservar decoder completo con segmentos pequeños y caché MP3.

No introducir decoder nativo, ExoPlayer o prefetch solo por una métrica teórica.

## WebSocket persistente

Después de las fases 1 y 2, se puede implementar sesión persistente con un solo turn activo, correlación estricta, cierre de cola terminal, reconexión limitada, invalidación por cambio de UA/Origin/token/URL/configuración y renovación de contexto tras 403. No asumir duración indefinida ni prometer un handshake por capítulo hasta medirlo.

## Rendimiento medido

Solo después de medir optimizar copias de `ByteString`, Regex, hex y logs calientes. Estas mejoras son secundarias frente a cancelación, caché y latencia.

## Salida de fase

Comparar antes/después: primer audio, memoria máxima, bytes de red, tiempo total, bitrate/cache hit, duración de caché y tiempo de cancelación. Probar red lenta, audio corrupto, cierre durante frame, cambios de configuración y textos largos.

# Fase 4 — Android, release y limpieza

## Manifest y compatibilidad

Revisar `BIND_TEXT_SERVICE`, metadata, `android:exported`, `allowBackup`, `android:localeConfig` y `res/xml/locales_config.xml`.

Revisar `directBootAware`: eliminarlo si el código usa `cacheDir`/DataStore protegido por credenciales antes del desbloqueo, o migrar explícitamente al almacenamiento device-protected. No declarar un soporte Direct Boot que el código no pueda cumplir.

Probar descubrimiento y síntesis en API mínima, Android 13+ y versión reciente; validar Settings, Play Books y al menos un cliente TTS real.

## `Voice`, HTTP, release y lint

Construir `Voice` con constructor público de seis parámetros. Resolver default con `onGetDefaultVoiceNameFor()`; eliminar APIs ocultas/reflexión.

Compartir `OkHttpClient` en `Application`/infraestructura. No crear clientes por recreación de Activity.

Activar progresivamente:

```kotlin
release {
    isMinifyEnabled = true
    isShrinkResources = true
    proguardFiles(
        getDefaultProguardFile("proguard-android-optimize.txt"),
        "proguard-rules.pro"
    )
}
```

Primero ejecutar tests JVM, instrumentados y pruebas manuales con release minificado. Elevar lint; no ocultar fallos con `abortOnError = false`. Revisar R8 para servicio, metadata, JSON y cualquier reflexión legítima restante.

## Configuración editable

User-Agent: no vacío, ASCII imprimible, sin CR/LF/controles y longitud razonable. Origin: esquema/host válidos, sin CR/LF y compatible con OkHttp. Guardar solo valores válidos; ante valores heredados inválidos usar defaults y ofrecer restauración.

## Código muerto, locale y errores

Eliminar o aislar `stripRiffHeader`, ramas inalcanzables, constantes RIFF no soportadas y predicados sin llamadores. Decidir si `locale` se elimina o se deriva realmente de la voz y se usa en `xml:lang`; no conservar una preferencia falsa.

Sustituir mensajes hardcodeados en español por códigos de error/`sealed class` y `strings.xml`.

Actualizar README con versión real, tests existentes, red requerida, datos enviados, endpoint variable, caché/privacidad, compatibilidad comprobada, límites y comandos de build. No afirmar streaming, fallback offline, catálogo completo o compatibilidad Android no probada.

## Salida de fase

- `test` JVM verde.
- instrumentación sin red real por defecto.
- lint sin errores bloqueantes.
- release minificado instalado y probado.
- motor visible en Ajustes.
- síntesis corta/larga, cancelación, cambios de voz/idioma y caché verificados.
- catálogo vacío/403 con degradación clara.
- cero tokens, SSML completo, texto o audio sensible en logs.

## 4. Matriz de hallazgos

| Hallazgos | Tratamiento |
|---|---|
| F01 streaming tardío | Fase 3 condicionada; mientras tanto segmentos operativos menores |
| F02 catálogo sin DRM | Fase 2 obligatorio |
| F03 catálogo sin memoización | Fase 2 obligatorio |
| F04 reflexión `Voice` | Fase 4 obligatorio |
| F05 DataStore bloqueante | Fase 2 obligatorio |
| F06 slider por píxel | Fase 2 obligatorio |
| F07 diagnóstico por frame | Fase 2 obligatorio |
| F08 carrera de cancelación | Fase 2 obligatorio |
| F09 controles inválidos | Fase 1 obligatorio |
| F10 ausencia de tests JVM | Fase 1 obligatorio |
| F11 terminalidad al cancelar | Fase 1 obligatorio |
| F12 escritura no atómica | Fase 3 obligatorio |
| F13 PCM en caché | Fase 3 obligatorio |
| F14 timeout global | Fase 2 obligatorio |
| F15 ISO3 costoso | Fase 2 obligatorio |
| F16 snapshot bloqueante | Fase 2 obligatorio |
| F17 R8/lint | Fase 4 obligatorio antes de release |
| F18 deriva no atómica | Fase 2 obligatorio |
| F19 hot paths | Fase 3/4, después de medir |
| F20 UA/Origin sin validar | Fase 4 obligatorio |
| F21 OkHttp por Activity | Fase 4 obligatorio |
| F22 decoder espera demasiado | Fase 3 obligatorio |
| F23 clave ambigua | Fase 3 recomendable |
| F24 locale falso | Fase 4 decidir y corregir |
| F25 código muerto | Fase 4 |
| F26 logs calientes | Fase 2/4 |
| F27 errores solo en español | Fase 4 |
| F28 localeConfig/Direct Boot | Fase 4 |
| F29 README/token duplicado | Fase 4 |
| F30 copias de frames | Fase 3, solo tras medir/streaming |

## 5. No-regresión

No aceptar una fase si:

- una solicitud queda sin terminal;
- `onStop()` deja conexión, latch, decoder, executor o prefetch vivos;
- 403 deja el reloj global desviado permanentemente;
- un catálogo corrupto sustituye uno válido;
- una clave sirve audio con voz/rate/pitch/formato/protocolo distintos;
- un segmento excede el límite probado o contiene UTF-8 inválido;
- un Content-Type inesperado se reproduce como MP3 válido sin diagnóstico/validación;
- UI, Binder o WebSocket hacen I/O síncrono;
- release minificado rompe el motor/decoder;
- logs contienen TrustedClientToken, MUID, cookies, SSML, texto o audio del usuario.

## 6. Orden de impacto

1. Tests JVM, terminalidad, sanitización, segmentación y parser.
2. DRM/catálogo, cancelación, snapshot y watchdog.
3. Caché MP3 atómica y decoder fail-fast.
4. Segmentos operativos menores y medición.
5. WebSocket persistente, si las pruebas justifican su riesgo.
6. Streaming incremental, solo si `MediaCodec` es compatible.
7. Manifest, release, lint, errores localizados y README.

El resultado debe conservar la simplicidad del proyecto y hacer explícitos sus contratos: texto sanitizado, segmentación segura, protocolo aislado, MP3 diferenciado de PCM, estado cancelable, configuración cacheada, almacenamiento atómico, caché eficiente y compatibilidad Android realmente verificada.
