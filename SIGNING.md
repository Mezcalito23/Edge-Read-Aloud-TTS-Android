# Firmar la APK (una sola vez)

Sin este paso, `assembleRelease` usa la clave **debug** de Android Studio.
Eso vale para instalar en el tablet. **No** vale para una APK pública en
GitHub que quieras actualizar después sin desinstalar.

Hazlo en la carpeta del proyecto (`Edge Read Aloud`), Git Bash de Android
Studio.

## 1. Crear el almacén (JKS)

```bash
keytool -genkeypair -v -keystore release.jks -keyalg RSA -keysize 2048 \
  -validity 10000 -alias edge-tts
```

Pregunta nombre, ciudad, etc. (pueden ser ficticios). **Memoriza o anota**
las dos contraseñas (almacén y clave). Si las pierdes junto con el `.jks`,
hay que desinstalar la app en cada dispositivo para instalar otra firma.

## 2. Archivo de contraseñas (no se sube a Git)

```bash
cp keystore.properties.example keystore.properties
```

Edita `keystore.properties`:

```
storeFile=release.jks
storePassword=TU_PASSWORD_DEL_ALMACEN
keyAlias=edge-tts
keyPassword=TU_PASSWORD_DE_LA_CLAVE
```

`release.jks` y `keystore.properties` están en `.gitignore`.

## 3. Compilar la APK firmada

```bash
./gradlew assembleRelease
```

La APK queda en:

`app/build/outputs/apk/release/app-release.apk`

Ese archivo es el que subes a GitHub Releases.

## 4. Copia de seguridad

Copia `release.jks` a un USB / nube privada. Sin él no hay actualizaciones
in-place.

Si **ya** instalaste una APK debug y luego instalas esta firmada, Android
pedirá desinstalar primero (firma distinta). Por eso conviene firmar **antes**
de la primera APK que compartas.
