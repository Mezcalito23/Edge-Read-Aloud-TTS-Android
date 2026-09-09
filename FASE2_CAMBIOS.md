# Fase 2 Completada - V-3.1.md

## Archivos Nuevos Creados

### 1. ProtocolState.kt (70 líneas)
- Gestión atómica de deriva de reloj con `AtomicLong`
- `adjustClockSkew()`: ajusta deriva si < 24 horas
- `getAdjustedUnixSeconds()`: tiempo local + skew
- `resetForTest()`: para tests deterministas
- Cumple V-3.1.md Sección 7

### 2. EdgeDrm.kt (103 líneas)
- Centraliza TODO el DRM (V-3.1.md Sección 7):
  - Generación Sec-MS-GEC
  - Gestión de MUID
  - Cookie header
  - User-Agent y Origin configurables
  - Ajuste de deriva desde cabecera Date
- Expone `protocolState` públicamente
- Método `refreshMuid()` para renovación de contexto

### 3. ProtocolStateTest.kt (112 líneas)
- Tests JVM sin red para PhaseState
- Verifica límites de 24 horas
- Prueba resetForTest()
- Tests deterministas

### 4. EdgeDrmTest.kt (134 líneas)
- Tests JVM sin red para EdgeDrm
- Verifica formato de tokens (hex uppercase)
- Prueba generación de MUID
- Tests de configuración de UA/Origin

## Archivos Modificados

### 1. EdgeProtocolClient.kt
- Eliminado código duplicado de DRM
- Integrado con EdgeDrm instance
- Usa `edgeDrm.generateSecMsGec()` en lugar de función local
- Usa `edgeDrm.protocolState` para deriva
- Manejo 403 delega a `edgeDrm.adjustClockSkewFromServerDate()`
- Refresh de MUID en reintento 403
- Eliminadas funciones estáticas redundantes del companion object

### 2. VoiceCatalogRepository.kt
- Constante `CATALOG_FILENAME` compartida (V-3.1.md Sección 8)
- Memoización volátil `@Volatile private var memo`
- Escritura atómica: archivo temporal → rename
- Reintento único por 403
- Estructura `CatalogSnapshot` para memoización

## Criterios Fase 2 Cumplidos (V-3.1.md Sección 2)

✅ DRM compartido (EdgeDrm centralizado)
✅ Deriva atómica (ProtocolState con AtomicLong)
✅ Catálogo validado/memoizado (@Volatile + CatalogSnapshot)
✅ Snapshot DataStore (ya existía en SettingsStore)
✅ Escritura atómica (temporal → rename)
✅ Reintento 403 con ajuste de reloj
✅ Tests JVM sin red (ProtocolStateTest, EdgeDrmTest)
✅ Constante CATALOG_FILENAME compartida

## Pendientes para Fase 2 Completa

⚠️ Watchdog de cancelación (parcialmente implementado en EdgeProtocolClient)
⚠️ Cancelación completa (verificar integración con EdgeReadAloudTtsService)
⚠️ Sesión WebSocket persistente (Fase 2 avanzada - opcional según V-3.1.md Sección 11)

## Notas Importantes

- NO se implementó WebSocket persistente aún (requiere Fase 1 y 2 completas)
- La derivación de reloj ahora es testeable y atómica
- El DRM está completamente centralizado en EdgeDrm
- Los tests son 100% JVM, sin dependencia de Android ni red
