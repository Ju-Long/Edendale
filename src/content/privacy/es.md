---
updated: 2026-11-11
summary:
  - Edendale no tiene sistema de cuentas ni servidores propios. Las apps no contienen analíticas, publicidad ni rastreo.
  - Tu biblioteca, tus ajustes, tus credenciales guardadas y el control parental se quedan en tu dispositivo. Lo que se sincroniza pasa por un almacenamiento o un servicio que controlas tú, como iCloud o tu propio Google Drive, OneDrive, Dropbox o servidor.
  - La sincronización entre dispositivos está desactivada hasta que la activas. Entonces cada dispositivo escribe tu historial de visionado como un archivo legible en el almacenamiento que elijas, y las credenciales solo pasan directamente de uno de tus dispositivos a otro.
  - Edendale se conecta a Google Drive, OneDrive y Dropbox directamente desde tu dispositivo, solo para listar y reproducir tus vídeos y mantener sus propios archivos de sincronización. En Google Drive solo ve los vídeos que eliges y los archivos que él mismo crea.
  - Los detalles de películas, la búsqueda de subtítulos, los avisos para omitir y los tráileres contactan con los servicios que se indican abajo, solo para el fin descrito.
---

## A quién se aplica esta política

Esta política cubre las apps de Edendale para dispositivos Apple (iPhone, iPad,
Mac, Apple TV y Apple Vision Pro), Android (incluidos los visores Meta Quest) y
Windows, y este sitio web, edendale.babasama.com. Edendale es un proyecto libre
y de código abierto que se desarrolla en público en
[github.com/Ju-Long/Edendale](https://github.com/Ju-Long/Edendale). «Nosotros»
se refiere a las personas que lo desarrollan.

## No recopilamos tus datos

Edendale no tiene sistema de cuentas y no utiliza servidores que reciban
información de las apps. Las apps no contienen código de analíticas,
publicidad, rastreo ni informes de fallos. Tu información nunca nos llega, así
que no tenemos nada que vender, alquilar ni compartir.

Si permites que tu dispositivo comparta diagnósticos con los desarrolladores de
apps, la tienda desde la que instalaste Edendale (Apple, Google, Meta o
Microsoft) puede facilitarnos informes de fallos y estadísticas de uso
agregadas según su propia política de privacidad. Solo los usamos para corregir
problemas.

## Lo que se queda en tu dispositivo

- **Tu biblioteca:** las carpetas y fuentes que añades; los nombres, tamaños,
  fechas y duraciones de los archivos que Edendale encuentra en ellas; y la
  película o el episodio con el que se identificó cada archivo.
- **Tus ajustes:** las preferencias de reproducción, audio, imagen, subtítulos
  y controles, incluidas las elecciones que se recuerdan para cada título.
- **Los subtítulos que descargas.**
- **Credenciales guardadas y cuentas vinculadas:** contraseñas de servidores,
  claves de acceso de S3 y tokens de inicio de sesión en la nube, incluida la
  credencial de tu ubicación de sincronización, guardados en el almacenamiento
  protegido del sistema: el llavero (Keychain) en los dispositivos Apple, un
  almacenamiento cifrado con el Android Keystore y la protección de datos de
  Windows (DPAPI). Los tokens de acceso de corta duración solo se guardan en
  memoria.
- **Control parental:** los límites de clasificación por edades, los demás
  interruptores del control parental y el PIN parental, que solo se guarda
  como un hash lento con sal. Edendale nunca los sincroniza, nunca los envía a
  otro dispositivo ni a ningún otro lugar.

Edendale lee los nombres de archivo en tu dispositivo para reconocer películas
y episodios antes de contactar con cualquier servicio en línea, y nunca sube
tus vídeos.

## Qué se puede sincronizar y dónde

Edendale solo sincroniza a través de servicios que controlas tú, y solo cuando
los has activado:

- **Sincronización entre dispositivos (Edendale 27.1 y posteriores):** tu
  historial de visionado, a través del almacenamiento que elijas, como se
  describe en la sección siguiente.
- **Dispositivos Apple:** con iCloud, tu progreso, tus valoraciones, tus
  favoritos y tu lista se sincronizan a través de tu base de datos privada de
  iCloud. Las cuentas vinculadas y las credenciales guardadas se sincronizan
  mediante el llavero de iCloud con tu iPhone, iPad, Mac y Apple Vision Pro.
  El Apple TV guarda sus propias copias.
- **Windows:** si activaste la replicación con OneDrive antes de Edendale
  27.1, tu progreso y el estado de tus títulos se copian a través de una
  carpeta de tu propio OneDrive. Las credenciales y las cuentas nunca salen
  del dispositivo. Edendale 27.1 la sustituye por la sincronización entre
  dispositivos: lee esos archivos una vez y los deja donde están.
- **Android:** la copia de seguridad del propio Android puede incluir tu
  biblioteca y tus datos de visionado. Las credenciales, las claves, los
  tokens de cuentas, los ajustes de sincronización, y el PIN parental y los
  límites de clasificación quedan excluidos de las copias de seguridad y de
  las transferencias entre dispositivos.
- **Meta Quest:** si la copia de seguridad en la nube está activada en los
  ajustes del visor, la copia de Meta usa la de Android, con las mismas
  exclusiones. Al quitar la app del visor se borra todo lo que guardó en él.
  Meta conserva sus copias de seguridad, y restaura la más reciente si vuelves
  a instalar Edendale, hasta que las borres desde la copia de seguridad en la
  nube de los ajustes del visor; consulta
  [la ayuda de Meta sobre la copia de seguridad en la nube](https://www.meta.com/help/quest/399621398228171/).
- **Tu cuenta de TMDB (opcional):** si inicias sesión en The Movie Database,
  Edendale mantiene tus favoritos, tu lista y tus valoraciones sincronizados
  con esa cuenta. El progreso de visionado nunca se envía a TMDB.

## Sincronización entre dispositivos

A partir de Edendale 27.1, **Ajustes → Sincronización** puede mantener igual
tu historial de visionado en todos tus dispositivos. Está desactivada hasta
que la activas y eliges una ubicación. Edendale sigue sin tener servidores ni
sistema de cuentas: tus dispositivos comparten el historial solo a través de
un almacenamiento que ya es tuyo.

- **Qué contiene un archivo de sincronización:** cada dispositivo escribe un
  archivo, `Edendale/<nombre del espacio>/<ID del dispositivo>.json`, con tu
  progreso de visionado y si terminaste cada título, tus valoraciones,
  reseñas, favoritos y el estado de tu lista, cada uno identificado por el ID
  de TMDB del título; el nombre y la plataforma del dispositivo; un ID
  aleatorio creado para la sincronización; y la versión de Edendale. Nunca
  contiene tu biblioteca, nombres ni rutas de archivos, credenciales, tu
  sesión de TMDB ni el control parental.
- **Dónde se guarda:** en la ubicación que eliges y que es tuya: una carpeta
  de tu dispositivo (incluidos iCloud Drive o una carpeta que ofrezca otra app
  de almacenamiento en la nube), la carpeta de iCloud Drive propia de Edendale
  en los dispositivos Apple, tu Google Drive, OneDrive (en su carpeta de
  aplicaciones, `Apps/Edendale`), Dropbox o tu propio servidor WebDAV, SMB o
  SFTP. Cada dispositivo lee allí los archivos de los demás y los combina en
  el propio dispositivo. Los archivos son JSON sencillo y legible, así que
  cualquiera que pueda abrir esa carpeta o esa cuenta puede leer tu historial
  de visionado; Edendale te lo indica cuando eliges una ubicación.
- **Cómo dejar de sincronizar:** **Desactivar** en Ajustes → Sincronización
  detiene la sincronización en ese dispositivo y ofrece borrar su archivo; el
  historial del dispositivo se conserva. Al quitar un dispositivo en Ajustes →
  Sincronización se borra el archivo de ese dispositivo. En Android,
  **Eliminar historial** borra el progreso y las marcas de visto en el
  dispositivo y, con la sincronización activada, en tus otros dispositivos
  cuando vuelvan a sincronizarse. Después, los archivos de sincronización
  conservan durante un año como máximo una marca por cada título borrado, sin
  su progreso, para que una copia más antigua no pueda recuperarlo. Para
  quitarlo todo, desactiva la sincronización en cada dispositivo y borra la
  carpeta `Edendale` de tu almacenamiento.
- **Configurar otro dispositivo:** **Configurar otro dispositivo** envía la
  ubicación de sincronización y su credencial, y las demás cuentas y
  credenciales que marques, directamente de uno de tus dispositivos a otro a
  través de tu red local. La conexión se cifra con una clave que los dos
  dispositivos acuerdan cuando escaneas un código QR o escribes un código que
  aparece en el otro dispositivo, y el dispositivo que envía te pide confirmar
  el nombre del otro antes de enviar nada. Nada pasa por nosotros ni por
  nadie más. Mientras la pantalla de emparejamiento está abierta, el
  dispositivo anuncia su nombre en tu red local para que el otro pueda
  encontrarlo. Entre plataformas a las que no se puede trasladar un inicio de
  sesión de Google, solo se envían la dirección de correo de la cuenta de
  Google, el nombre del espacio y el ID de la carpeta de sincronización, y
  tú inicias sesión en Google en el dispositivo nuevo.
- **Un dispositivo que quitas** conserva la credencial que recibió, y el
  historial que ya tiene, hasta que revoques el acceso de Edendale en tu
  proveedor de almacenamiento o cambies la contraseña del servidor.

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
  reproducir un tráiler. En los dispositivos Apple, y en los dispositivos
  Android que no sean Meta Quest, Edendale lo reproduce en el modo de
  privacidad mejorada de YouTube (youtube-nocookie.com). En Windows, abre el
  tráiler en youtube.com en tu navegador, y en Meta Quest, en el navegador o en
  la app de YouTube.
- **El almacenamiento que vinculas**, y el que eliges para la sincronización,
  descritos en la sección siguiente.

## El almacenamiento que vinculas

Edendale reproduce vídeos de carpetas de tu dispositivo y del almacenamiento
que vinculas: servidores SMB, NFS, SFTP y WebDAV, almacenamiento compatible
con S3, Google Drive, OneDrive y Dropbox. Los servicios disponibles varían
según la plataforma. Cada conexión va directamente de tu dispositivo al
servicio que elegiste. Nada pasa por un servidor que gestionemos nosotros.

- **Inicio de sesión:** en Google Drive, OneDrive y Dropbox inicias sesión en
  la página del propio proveedor mediante OAuth 2.0 con PKCE, así que Edendale
  nunca ve tu contraseña. Las credenciales de servidores (nombres de usuario,
  contraseñas y claves de acceso) solo se envían al servidor al que
  pertenecen.
- **Acceso limitado:** Google solo recibe `drive.file`, que abarca los vídeos
  que eliges en el selector de archivos de Google y los archivos que crea
  Edendale, y nada más de tu Drive. Microsoft: `Files.Read`,
  `Files.ReadWrite.AppFolder`, `User.Read` y `offline_access`. Dropbox:
  `account_info.read`, `files.metadata.read`, `files.content.read` y
  `files.content.write`. Edendale solo lee tus vídeos: nunca los sube,
  cambia, comparte ni borra, ni nada más de tu almacenamiento. Los únicos
  archivos que escribe o borra son sus propios archivos de sincronización, y
  solo si activas la sincronización: en su carpeta `Edendale` de Google
  Drive, de Dropbox o del servidor o la carpeta que elegiste, y en la carpeta
  de aplicaciones de OneDrive.
- **Lo que Edendale lee:** el ID, la dirección de correo y el nombre de tu
  cuenta, para identificarla y mantener separadas sus fuentes; los nombres,
  tamaños, fechas y duraciones de los archivos y carpetas de las ubicaciones
  que exploras y vinculas o, en Google Drive, de los vídeos que eliges; el
  contenido de un vídeo solo mientras lo reproduces; y, si activas la
  sincronización, los archivos de sincronización de tus dispositivos.
- **Lo que Edendale guarda:** los datos de los archivos pasan a formar parte
  de tu biblioteca en el dispositivo. Los tokens de inicio de sesión y las
  credenciales van al almacenamiento protegido, como se describe arriba. Los
  datos de vídeo se mantienen en memoria mientras se reproducen y nunca se
  guardan en el disco.
- **Televisores:** un Apple TV puede recibir una cuenta o una credencial desde
  tu iPhone o iPad, y un Android TV desde Edendale en otro dispositivo
  Android, mediante una conexión cifrada en tu red local, solo después de que
  inicies la transferencia en el televisor y la confirmes en el otro
  dispositivo. En un televisor, OneDrive también puede iniciar sesión con un
  código que apruebas en otro dispositivo. A partir de Edendale 27.1,
  cualquiera de tus dispositivos puede configurar otro de esta forma, como se
  describe en «Sincronización entre dispositivos».

## Datos de usuario de Google

Cuando vinculas Google Drive, eliges vídeos en el selector de archivos de
Google, que se abre al iniciar sesión y cada vez que usas **Elegir más
vídeos**. Si inicias sesión en Google Drive solo para guardar allí los
archivos de sincronización, el selector no se abre. Edendale accede a:

- los vídeos que eliges, y nada más de tu Google Drive (`drive.file`):
  Edendale los lista, lee sus nombres, tamaños, fechas y duraciones de vídeo,
  y transmite los que eliges reproducir. Google comparte tu selección entre
  las apps de Edendale, así que un vídeo que eliges en un dispositivo también
  puede aparecer en otro con la misma cuenta de Google;
- si eliges Google Drive para la sincronización, la carpeta `Edendale` y los
  archivos de sincronización que el propio Edendale crea en ella, que lee,
  sustituye y borra como se describe en «Sincronización entre dispositivos»;
  y
- el ID de usuario de Google Drive, la dirección de correo y el nombre de tu
  cuenta, que Google Drive proporciona con ese mismo permiso, para mostrar
  qué cuenta está vinculada y distinguir tus cuentas.

Edendale usa estos datos únicamente para ofrecer su fuente de Google Drive
(listar los vídeos que elegiste y reproducirlos) y, si así lo eliges, para
mantener tus archivos de sincronización en tu Drive. Nunca sube, cambia,
comparte ni borra tus vídeos ni ningún otro archivo de tu Drive. Como con
cualquier fuente, Edendale lee los nombres de archivo en tu dispositivo para
reconocer películas y episodios, y solo envía a TMDB el título y el año
reconocidos para buscar los detalles.

Los datos se quedan en tus dispositivos y en tu propio Drive: los datos de los
archivos, en tu biblioteca; y la cuenta vinculada (su ID, su dirección de
correo, su nombre y su token de inicio de sesión), en el almacenamiento
protegido, como se describe arriba. En los dispositivos Apple, el llavero de
iCloud sincroniza la cuenta con tus otros dispositivos Apple. Solo llega a
otro dispositivo cuando confirmas una transferencia desde uno de los tuyos;
entre plataformas a las que no se puede trasladar un inicio de sesión de
Google, solo se envían la dirección de correo y el ID de la carpeta de
sincronización. Si quitas un vídeo de tu biblioteca, Edendale guarda su ID de
archivo solo en ese dispositivo, para que no vuelva a aparecer en la lista.
Los datos de usuario de Google nunca se nos envían, ni a ningún servidor que
gestionemos, así que nunca los vemos ni los leemos. Nunca se venden, nunca se
usan para publicidad y nunca se usan para desarrollar, mejorar o entrenar
modelos de inteligencia artificial o de aprendizaje automático.

Para retirar el acceso de Edendale, quita la fuente (lo que también elimina
sus archivos de tu biblioteca) y cierra sesión en **Ajustes → Cuentas**.
**Cerrar sesión y revocar acceso** también revoca el acceso de Edendale en
Google. Puedes retirarlo en cualquier momento desde las
[conexiones con terceros de tu cuenta de Google](https://myaccount.google.com/connections).
Al revocar el acceso, este termina en todos los dispositivos, incluidos los
televisores, y Google olvida los vídeos que elegiste. Los archivos de
sincronización que creó Edendale se quedan en tu Drive hasta que los borres.
Al eliminar la app se borra todo lo que guardó en ese dispositivo. Una copia
de seguridad en Android o Meta Quest puede conservar una copia de tu
biblioteca, pero nunca la cuenta vinculada ni su token de inicio de sesión.

El uso y la transferencia a cualquier otra app, por parte de Edendale, de la
información recibida de las API de Google se ajustarán a la
[Google API Services User Data Policy](https://developers.google.com/terms/api-services-user-data-policy)
(política de datos de usuario de los servicios de API de Google), incluidos
los requisitos de uso limitado (Limited Use).

## Cuentas de Microsoft y Dropbox

OneDrive y Dropbox funcionan de forma muy parecida: acceso de lectura a las
carpetas que exploras y vinculas, usado solo para listar y reproducir tus
vídeos, y, si eliges uno de ellos para la sincronización, acceso de escritura
usado solo para los archivos de sincronización de Edendale, en la carpeta de
aplicaciones de OneDrive (`Apps/Edendale`) o en la carpeta `Edendale` de tu
Dropbox. Edendale nunca cambia ni borra nada más allí. Los datos de la cuenta
y los tokens de inicio de sesión solo se guardan en tus dispositivos. Cierra
sesión en **Ajustes → Cuentas**. En Dropbox, **Cerrar sesión y revocar
acceso** también retira el acceso de Edendale en Dropbox. También puedes
quitar Edendale de las
[apps que pueden acceder a tu cuenta de Microsoft](https://account.live.com/consent/Manage)
o de tus
[apps conectadas de Dropbox](https://www.dropbox.com/account/connected_apps).
Los archivos de sincronización se quedan en tu almacenamiento hasta que los
borres. Una cuenta de Microsoft profesional o educativa puede estar gestionada
por tu organización.

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
una fuente, cerrar sesión en una cuenta, desactivar la sincronización entre
dispositivos, la sincronización con iCloud, la replicación con OneDrive o la
copia de seguridad de tu dispositivo, borrar los archivos de sincronización de
tu almacenamiento o eliminar la app. Como no tenemos ninguno de tus datos
personales, esos controles son la forma de ejercer tus derechos de acceso o
supresión. Los datos que conservan los servicios mencionados se rigen por sus
propias políticas de privacidad.

## Cambios en esta política

Cuando cambie la forma en que las apps tratan los datos, actualizaremos esta
página y la fecha que aparece arriba. Cada revisión es pública en el
historial del proyecto en GitHub.

## Contacto

Puedes enviar preguntas sobre esta política o sobre la privacidad en Edendale
abriendo una incidencia en
[github.com/Ju-Long/Edendale/issues](https://github.com/Ju-Long/Edendale/issues).
