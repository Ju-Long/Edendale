---
updated: 2026-10-06
summary:
  - Edendale no tiene sistema de cuentas ni servidores propios. Las apps no contienen analíticas, publicidad ni rastreo.
  - Tu biblioteca, tus ajustes y tus credenciales guardadas se quedan en tu dispositivo. Lo que se sincroniza pasa por un servicio que controlas tú, como iCloud o tu propio OneDrive.
  - Google Drive, OneDrive y Dropbox reciben acceso de solo lectura, se consultan directamente desde tu dispositivo y solo se usan para listar y reproducir tus vídeos.
  - Los detalles de películas, la búsqueda de subtítulos, los avisos para omitir y los tráileres contactan con los servicios que se indican abajo, solo para el fin descrito.
---

## A quién se aplica esta política

Esta política cubre las apps de Edendale para dispositivos Apple (iPhone, iPad,
Mac, Apple TV y Apple Vision Pro), Android y Windows, y este sitio web,
edendale.babasama.com. Edendale es un proyecto libre y de código abierto que se
desarrolla en público en
[github.com/Ju-Long/Edendale](https://github.com/Ju-Long/Edendale).
«Nosotros» se refiere a las personas que lo desarrollan.

## No recopilamos tus datos

Edendale no tiene sistema de cuentas y no utiliza servidores que reciban
información de las apps. Las apps no contienen código de analíticas,
publicidad, rastreo ni informes de fallos. Tu información nunca nos llega, así
que no tenemos nada que vender, alquilar ni compartir.

Si permites que tu dispositivo comparta diagnósticos con los desarrolladores
de apps, la tienda desde la que instalaste Edendale (Apple, Google o Microsoft)
puede facilitarnos informes de fallos y estadísticas de uso agregadas según su
propia política de privacidad. Solo los usamos para corregir problemas.

## Lo que se queda en tu dispositivo

- **Tu biblioteca:** las carpetas y fuentes que añades; los nombres, tamaños,
  fechas y duraciones de los archivos que Edendale encuentra en ellas; y la
  película o el episodio con el que se identificó cada archivo.
- **Tus ajustes:** las preferencias de reproducción, audio, imagen, subtítulos
  y controles, incluidas las elecciones que se recuerdan para cada título.
- **Los subtítulos que descargas.**
- **Credenciales guardadas y cuentas vinculadas:** contraseñas de servidores,
  claves de acceso de S3 y tokens de inicio de sesión en la nube, guardados en
  el almacenamiento protegido del sistema: el llavero (Keychain) en los
  dispositivos Apple, un almacenamiento cifrado con el Android Keystore y la
  protección de datos de Windows (DPAPI). Los tokens de acceso de corta
  duración solo se guardan en memoria.

Edendale lee los nombres de archivo en tu dispositivo para reconocer películas
y episodios antes de contactar con cualquier servicio en línea, y nunca sube
tus vídeos.

## Qué se puede sincronizar y dónde

Edendale solo sincroniza a través de servicios que controlas tú, y solo cuando
los has activado:

- **Dispositivos Apple:** con iCloud, tu progreso, tus valoraciones, tus
  favoritos y tu lista se sincronizan a través de tu base de datos privada de
  iCloud. Las cuentas vinculadas y las credenciales guardadas se sincronizan
  mediante el llavero de iCloud con tu iPhone, iPad, Mac y Apple Vision Pro.
  El Apple TV guarda sus propias copias.
- **Windows:** si activas la replicación con OneDrive, tu progreso y el estado
  de tus títulos se copian a través de una carpeta de tu propio OneDrive. Las
  credenciales y las cuentas nunca salen del dispositivo.
- **Android:** la copia de seguridad del propio Android puede incluir tu
  biblioteca y tus datos de visionado. Las credenciales, las claves y los
  tokens de cuentas quedan excluidos de las copias de seguridad y de las
  transferencias entre dispositivos.
- **Tu cuenta de TMDB (opcional):** si inicias sesión en The Movie Database,
  Edendale mantiene tus favoritos, tu lista y tus valoraciones sincronizados
  con esa cuenta. El progreso de visionado nunca se envía a TMDB.

## Servicios en línea que usa Edendale

Cada servicio de esta lista recibe la dirección IP de tu dispositivo, como
cualquier conexión a internet, además de lo siguiente:

- **[TMDB](https://www.themoviedb.org/privacy-policy)** (The Movie Database),
  para los detalles y las imágenes de películas y series: el título y el año
  que Edendale lee de un nombre de archivo (nunca el nombre completo, su
  carpeta ni el propio archivo) y los ID de TMDB de los títulos que consultas.
  Si inicias sesión, también tu sesión de TMDB.
- **[Wyzie Subs](https://wyzie.io/privacy)**, solo cuando buscas subtítulos en
  línea: el ID de TMDB del título, los números de temporada y episodio, los
  idiomas que pides y tu clave de API.
- **[TheIntroDB](https://theintrodb.org/docs/privacy)**, solo mientras Avisos
  para omitir esté activado (viene desactivado): el ID de TMDB del título, los
  números de temporada y episodio, y la duración del vídeo.
- **[YouTube](https://policies.google.com/privacy)**, solo cuando eliges
  reproducir un tráiler. En los dispositivos Apple y Android, Edendale lo
  reproduce en el modo de privacidad mejorada de YouTube
  (youtube-nocookie.com). En Windows, abre el tráiler en youtube.com en tu
  navegador.
- **El almacenamiento que vinculas**, descrito en la sección siguiente.

## El almacenamiento que vinculas

Edendale reproduce vídeos de carpetas de tu dispositivo y del almacenamiento
que vinculas: servidores SMB, NFS, SFTP y WebDAV, almacenamiento compatible
con S3, Google Drive, OneDrive y Dropbox. Los servicios disponibles varían
según la plataforma; Google Drive está disponible por ahora en los
dispositivos Apple. Cada conexión va directamente de tu dispositivo al
servicio que elegiste. Nada pasa por un servidor que gestionemos nosotros.

- **Inicio de sesión:** en Google Drive, OneDrive y Dropbox inicias sesión en
  la página del propio proveedor mediante OAuth 2.0 con PKCE, así que Edendale
  nunca ve tu contraseña. Las credenciales de servidores (nombres de usuario,
  contraseñas y claves de acceso) solo se envían al servidor al que
  pertenecen.
- **Acceso de solo lectura:** Edendale solicita permisos de solo lectura.
  Google: `openid`, `email` y `drive.readonly`. Microsoft: `Files.Read`,
  `User.Read` y `offline_access`. Dropbox: `account_info.read`,
  `files.metadata.read` y `files.content.read`. Edendale no puede crear,
  cambiar, compartir ni borrar nada de tu almacenamiento.
- **Lo que Edendale lee:** el ID y la dirección de correo de tu cuenta, para
  identificarla y mantener separadas sus fuentes; los nombres, tamaños, fechas
  y duraciones de los archivos y carpetas de las ubicaciones que exploras y
  vinculas; y el contenido de un vídeo solo mientras lo reproduces.
- **Lo que Edendale guarda:** los datos de los archivos pasan a formar parte
  de tu biblioteca en el dispositivo. Los tokens de inicio de sesión y las
  credenciales van al almacenamiento protegido, como se describe arriba. Los
  datos de vídeo se mantienen en memoria mientras se reproducen y nunca se
  guardan en el disco.
- **Televisores:** un Apple TV puede recibir una cuenta o una credencial desde
  tu iPhone o iPad mediante una conexión cifrada en tu red local, solo después
  de que inicies la transferencia en el televisor y la confirmes en el
  teléfono o la tableta. En un televisor, OneDrive también puede iniciar
  sesión con un código que apruebas en otro dispositivo.

## Datos de usuario de Google

Cuando vinculas Google Drive, Edendale accede a:

- el ID único y la dirección de correo de tu cuenta de Google (`openid` y
  `email`), para mostrar qué cuenta está vinculada y distinguir tus cuentas;
  y
- los archivos y carpetas de tu Google Drive (`drive.readonly`): Edendale
  lista las carpetas que exploras y vinculas, lee los nombres, tamaños, fechas
  y duraciones de vídeo de sus archivos y transmite los vídeos que eliges
  reproducir.

Edendale usa estos datos únicamente para ofrecer su fuente de Google Drive:
buscar una carpeta, listar sus vídeos y reproducirlos. Como con cualquier
fuente, Edendale lee los nombres de archivo en tu dispositivo para reconocer
películas y episodios, y solo envía a TMDB el título y el año reconocidos para
buscar los detalles.

Los datos se quedan en tus dispositivos: los datos de los archivos, en tu
biblioteca; y la cuenta vinculada (su ID, su dirección de correo y su token de
inicio de sesión), en el llavero, que el llavero de iCloud sincroniza con tus
otros dispositivos Apple. Solo llega a un Apple TV cuando confirmas una
transferencia desde tu iPhone o iPad. Los datos de usuario de Google nunca se
nos envían, ni a ningún servidor que gestionemos, así que nunca los vemos ni
los leemos. Nunca se venden, nunca se usan para publicidad y nunca se usan
para desarrollar, mejorar o entrenar modelos de inteligencia artificial o de
aprendizaje automático.

Para retirar el acceso de Edendale, quita la fuente (lo que también elimina
sus archivos de tu biblioteca) y cierra sesión en **Ajustes → Cuentas**.
**Cerrar sesión y revocar acceso** también revoca el acceso de Edendale en
Google. Puedes retirarlo en cualquier momento desde las
[conexiones con terceros de tu cuenta de Google](https://myaccount.google.com/connections).
Al eliminar la app se borra todo lo que guardó en ese dispositivo.

El uso y la transferencia a cualquier otra app, por parte de Edendale, de la
información recibida de las API de Google se ajustarán a la
[Google API Services User Data Policy](https://developers.google.com/terms/api-services-user-data-policy)
(política de datos de usuario de los servicios de API de Google), incluidos
los requisitos de uso limitado (Limited Use).

## Cuentas de Microsoft y Dropbox

OneDrive y Dropbox funcionan igual: acceso de solo lectura, usado solo para
listar y reproducir tus vídeos, y guardado solo en tus dispositivos. Cierra
sesión en **Ajustes → Cuentas**. En Dropbox, **Cerrar sesión y revocar
acceso** también retira el acceso de Edendale en Dropbox. También puedes
quitar Edendale de las
[apps que pueden acceder a tu cuenta de Microsoft](https://account.live.com/consent/Manage)
o de tus
[apps conectadas de Dropbox](https://www.dropbox.com/account/connected_apps).
Una cuenta de Microsoft profesional o educativa puede estar gestionada por tu
organización.

## Este sitio web

Este sitio web es estático y está alojado en GitHub Pages. No usa cookies, no
guarda nada en tu navegador, no tiene formularios y no carga analíticas,
fuentes ni scripts de otros sitios. Elige un idioma a partir de la
configuración de tu navegador sin guardar nada, y el idioma que eliges solo
queda reflejado en la dirección de la página. GitHub, como proveedor de
alojamiento, recibe la información habitual de cada solicitud, como tu
dirección IP; consulta la
[GitHub General Privacy Statement](https://docs.github.com/en/site-policy/privacy-policies/github-general-privacy-statement).
Los enlaces que abren la app de Edendale se gestionan en tu dispositivo.

## Menores

Edendale no recopila a sabiendas información personal de nadie, tampoco de
menores. Las apps no nos envían nada, así que no hay nada que podamos
recopilar.

## Tus opciones

Puedes ver, cambiar o borrar tus datos en la app en cualquier momento: quitar
una fuente, cerrar sesión en una cuenta, desactivar la sincronización con
iCloud o la replicación con OneDrive, o eliminar la app. Como no tenemos
ninguno de tus datos personales, esos controles son la forma de ejercer tus
derechos de acceso o supresión. Los datos que conservan los servicios
mencionados se rigen por sus propias políticas de privacidad.

## Cambios en esta política

Cuando cambie la forma en que las apps tratan los datos, actualizaremos esta
página y la fecha que aparece arriba. Cada revisión es pública en el
historial del proyecto en GitHub.

## Contacto

Puedes enviar preguntas sobre esta política o sobre la privacidad en Edendale
abriendo una incidencia en
[github.com/Ju-Long/Edendale/issues](https://github.com/Ju-Long/Edendale/issues).
