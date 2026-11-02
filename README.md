# JhaKet IPTV · aplicación Android

Reproductor JhaKet empaquetado como app para teléfono Android, Android TV y Google TV.

## Qué resuelve respecto al archivo HTML

- **Los pedidos a internet funcionan.** Abriendo el `.html` desde Descargas, Android lo sirve
  como `content://` y el navegador bloquea las consultas a TMDB y a las guías EPG. Acá el
  contenido se sirve desde `https://appassets.androidplatform.net`, un origen real, y dejan
  de estar bloqueados.
- **Brillo real de la pantalla.** El deslizador ya no solo aclara la imagen: baja la luz del
  panel (solo mientras la app está abierta).
- **Ícono propio, pantalla completa, botón atrás** que cierra primero lo que esté abierto
  adentro, y la pantalla no se apaga sola.
- **Aparece en el menú del televisor**, con navegación por control remoto.

## Qué NO resuelve

Los usuarios, las contraseñas y los permisos de dispositivo **siguen guardándose en cada
teléfono**. Si otra persona instala el APK, en su equipo va a crear su propio administrador,
igual que pasaba con el archivo HTML. Administrar usuarios desde un solo lugar necesita un
servidor; es un trabajo aparte.

## Cómo obtener el APK sin instalar nada

1. Crear una cuenta en github.com (gratis).
2. Crear un repositorio nuevo y subir esta carpeta completa. Se puede hacer desde el
   navegador: **Add file → Upload files**, y arrastrar todo.
3. Entrar a la pestaña **Actions**. El flujo *Compilar APK* arranca solo; si no, se lanza
   a mano con **Run workflow**.
4. Cuando termina (unos minutos), abrir esa ejecución y descargar el archivo
   **JhaKet-IPTV-apk** desde la sección *Artifacts*.
5. En el teléfono, abrir el APK y permitir la instalación de orígenes desconocidos.

El APK se firma con la clave de depuración, que alcanza para instalarlo a mano. Para subirlo
a Google Play hay que generar una clave propia.

## Estructura

```
app/src/main/assets/index.html   el reproductor completo
app/src/main/java/...            envoltorio en Kotlin (WebView + puentes)
app/src/main/AndroidManifest.xml permisos y entradas de menú (teléfono y TV)
.github/workflows/build.yml      compilación automática en la nube
```

## Actualizar la app

Reemplazar `app/src/main/assets/index.html` por la versión nueva del reproductor, subir el
cambio y volver a descargar el APK. Conviene subir `versionCode` y `versionName` en
`app/build.gradle.kts` en cada versión.
