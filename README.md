# VideoTotal TV

Cliente de YouTube para **Android TV** con marca propia, licencia por código y
actualización desde la propia app.

Está construido sobre **[SmartTube](https://github.com/yuliskov/SmartTube)**
(MIT, de Yuri Lysikov), que ya resuelve lo caro: interfaz de 10 pies, navegación
con control remoto, reproductor propio **sin anuncios**, SponsorBlock y calidad
hasta 4K. Lo que se añade encima es la identidad de VideoTotal: nombre, icono,
banner, colores, la puerta de licencia y el actualizador.

## Qué cambia respecto de SmartTube

| Pieza | Dónde |
|---|---|
| `applicationId` `ec.videototal.tv`, versión 1.0.0 | `smarttubetv/build.gradle` |
| Nombre visible "VideoTotal TV", banner e iconos propios | `res/values/strings.xml`, `res/mipmap-nodpi/` |
| Pantalla de licencia como **único lanzador** | `smarttubetv/src/main/java/ec/videototal/tv/LicenseActivity.java` |
| Licencia contra el backend de VideoTotal | `LicenseApi.java`, `LicenseStore.java` |
| Actualizador desde los releases de este repo | `Updater.java` |
| Aviso de actualización de SmartTube apagado | `BootDialogPresenter.java` |
| `google-services.json` de SmartTube eliminado | (reportaba a su Firebase) |

## Cómo funciona la licencia

1. Al abrir, `LicenseActivity` mira si hay licencia guardada y vigente.
   - **Sí** → entra directo a la app (nunca vuelve a pedir el código).
   - **No** → pide el código y lo canjea con `POST /license/activate`.
2. El identificador del equipo es `ANDROID_ID`, así la licencia queda atada al
   televisor y aparece en el mismo panel que la versión de celular.
3. En segundo plano se revalida con `GET /me`. Si el servidor retira el plan, se
   borra la sesión; si no hay internet, se sigue funcionando con lo guardado.

## Cómo se publica una actualización

1. Subir `versionCode` y `versionName` en `smarttubetv/build.gradle`.
2. Crear un release con la etiqueta de la versión (por ejemplo `1.0.1`) y **el
   APK adjunto** (el universal es el más seguro: sirve en cualquier TV).
3. La app compara la versión y ofrece descargar e instalar.

El actualizador usa el `FileProvider` que ya trae la librería
`appupdatechecker2` (autoridad `<applicationId>.update_provider`) y descarga en
la caché interna (`cache-path "."`), que es lo que ese proveedor expone.

## Compilar

```bash
# JDK 17, SDK de Android con plataforma 34 y build-tools 30.0.3
chmod +x gradlew
./gradlew :smarttubetv:assembleStstableRelease
```

La firma se lee de `keystore.properties` en la raíz:

```properties
storeFile=/ruta/al/keystore.jks
storePassword=...
keyAlias=...
keyPassword=...
```

En CI (`.github/workflows/build-apk.yml`) ese archivo se genera desde los
secretos `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` y `KEY_PASSWORD`.

## Trampas que costaron tiempo

- **`.gitignore` de SmartTube ignora `gen/`**, y los 36 archivos de
  `.../browse/v2/gen`, `.../search/v2/gen`, etc. **son código fuente**, no
  salida de compilación. Sin ellos el build falla con `Unresolved reference`.
  Se subieron con `git add -f`.
- **El wrapper jar que trae SmartTube no pasa la validación** de
  `gradle/actions/setup-gradle`; hay que poner `validate-wrappers: false`.
- **`google-services.json`** contenía el cliente `app.smarttube.stable` y rompía
  el build con el `applicationId` propio: se eliminó (además dejaba de reportar
  a un Firebase ajeno).
- **Push bloqueado por secretos**: los fixtures de test de SmartTube traen
  tokens OAuth reales (`1//…`, `ya29.…`). Se redactaron.
- **`gradlew` sin permiso de ejecución** al commitear desde Windows: se arregla
  con `git update-index --chmod=+x gradlew` y un `chmod +x` en el CI.
- **`uses-feature leanback`** queda en `true` porque alguna librería lo exige;
  eso solo filtra en Google Play, no impide instalar el APK a mano.

## Licencia y créditos

Este proyecto es un fork de SmartTube (MIT). Se conserva su aviso de copyright
en `LICENSE`. El código propio de VideoTotal (licencia, marca, actualizador) es
GPL-3.0, igual que el resto de la familia VideoTotal.
