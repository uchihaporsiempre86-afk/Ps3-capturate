# PS3 Capture 480

Capturadora PS3 → Android (proyecto en desarrollo).

## Compilar el APK (GitHub Actions)

1. Sube el contenido de esta carpeta a un repositorio de GitHub (la raíz debe contener `.github/` y `Android/`).
2. Ve a **Actions → Build APK → Run workflow**.
3. Espera ~5 minutos. Descarga el artefacto **PS3Capture480-debug-apk**.
4. Instala `app-debug.apk` en el teléfono.

## Probar sin PS3

1. Abre la app → modo **Demo**: deben verse barras de color y un cuadrado naranja moviéndose.
2. Prueba el botón **Record**.
3. Cambia a **Source: Wi-Fi**. Anota la IP que muestra.
4. Desde un PC en la misma red (cuando tengas los tests de host):
   ```
   ./host_sender <IP_DEL_TELEFONO>
   ```

## Qué falta (lado PS3)

Ver `docs/PASOS_PS3.md`.
