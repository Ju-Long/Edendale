---
updated: 2026-11-16
summary:
  - O Edendale não tem sistema de contas nem servidores próprios. Os apps não contêm analytics, publicidade nem rastreamento.
  - Sua biblioteca, seus ajustes, seus logins salvos e o controle dos pais ficam no seu dispositivo. O que é sincronizado passa por um armazenamento ou serviço que você controla, como o iCloud ou o seu próprio Google Drive, OneDrive, Dropbox ou servidor.
  - A sincronização entre dispositivos fica desativada até você ativá-la. Então cada dispositivo grava seu histórico de reprodução como um arquivo legível no armazenamento que você escolher, e os logins só passam diretamente de um dispositivo seu para outro.
  - O Edendale acessa o Google Drive, o OneDrive e o Dropbox diretamente do seu dispositivo, só para listar e reproduzir seus vídeos e manter os próprios arquivos de sincronização. No Google Drive, ele só vê os vídeos que você escolhe e os arquivos que ele mesmo cria.
  - Detalhes de filmes, busca de legendas, botões para pular e trailers contatam os serviços indicados abaixo, apenas para a finalidade descrita.
---

## A quem esta política se aplica

Esta política abrange os apps do Edendale para dispositivos Apple (iPhone,
iPad, Mac, Apple TV e Apple Vision Pro), Android (incluindo os headsets Meta
Quest) e Windows, e este site, edendale.babasama.com. O Edendale é um projeto
livre e de código aberto, desenvolvido publicamente em
[github.com/Ju-Long/Edendale](https://github.com/Ju-Long/Edendale). “Nós” se
refere às pessoas que o desenvolvem.

## Não coletamos seus dados

O Edendale não tem sistema de contas e não opera servidores que recebam
informações dos apps. Fora o cinema do Meta Quest descrito abaixo,
os apps não contêm código de analytics, publicidade, rastreamento nem
relatórios de falhas. Suas informações nunca chegam até nós,
então não temos nada para vender, alugar ou compartilhar.

Se você permitir que seu dispositivo compartilhe diagnósticos com
desenvolvedores de apps, a loja em que você instalou o Edendale (Apple, Google,
Meta ou Microsoft) pode nos fornecer relatórios de falhas e estatísticas de uso
agregadas, de acordo com a política de privacidade dela. Usamos esses dados
apenas para corrigir problemas.

O cinema imersivo do Meta Quest, nas versões do Edendale que o incluem, é
feito com o Spatial SDK da Meta, que inclui o próprio código de analytics da
Meta. Segundo os termos da Meta para esse SDK, terceiros, incluindo a Meta,
podem coletar ou receber informações dos apps que o usam. O Edendale não faz
login na Meta por você nem usa os serviços de plataforma da Meta. O cinema usa
suas mãos e seus controles só para que você possa apontar e selecionar, e a
sala, a iluminação e o assento que você escolhe ficam guardados com seus
outros ajustes no headset.

## O que fica no seu dispositivo

- **Sua biblioteca:** as pastas e origens que você adiciona; os nomes,
  tamanhos, datas e durações dos arquivos que o Edendale encontra nelas; e o
  filme ou episódio associado a cada arquivo.
- **Seus ajustes:** preferências de reprodução, áudio, imagem, legendas e
  controles, incluindo as escolhas lembradas para cada título.
- **As legendas que você baixa.**
- **Logins salvos e contas vinculadas:** senhas de servidores, chaves de
  acesso do S3, tokens de login na nuvem (incluindo o login do seu local de
  sincronização), seu login do TMDB e sua chave de API de legendas, guardados
  no armazenamento protegido do sistema: as Chaves
  (Keychain) nos dispositivos Apple, um armazenamento criptografado com o
  Android Keystore e a Proteção de Dados do Windows (DPAPI). Tokens de acesso
  de curta duração ficam apenas na memória.
- **Controle dos pais:** a opção “Adequado para público jovem” e, no Android
  e no Meta Quest, também os limites de classificação indicativa, as demais
  opções do controle dos pais e o PIN dos pais, que é guardado apenas como um
  hash lento com sal. O Edendale nunca os sincroniza, nunca os envia a outro
  dispositivo nem a nenhum outro lugar. O próprio backup do seu dispositivo
  pode manter a opção “Adequado para público jovem”, mas nunca o PIN nem os
  limites de classificação.
- **O que o seu dispositivo mostra:** os títulos que você está assistindo,
  com a imagem e o progresso, podem aparecer fora do app: nos widgets do
  Edendale, no Top Shelf da Apple TV, na lista de atalhos da barra de tarefas
  do Windows, nos controles de mídia do sistema durante a reprodução e, se
  você ativar **Continuar assistindo na tela inicial**, na fileira
  “Continuar assistindo” da Android TV, que o app da tela inicial da TV pode
  ler. Qualquer pessoa que veja o seu dispositivo pode vê-los ali.

O Edendale lê os nomes dos arquivos no seu dispositivo para reconhecer filmes
e episódios antes de contatar qualquer serviço online, e nunca envia seus
vídeos para lugar nenhum.

## O que pode ser sincronizado, e onde

O Edendale só sincroniza por meio de serviços que você controla, e só quando
você os ativa:

- **Sincronização entre dispositivos (Edendale 27.1 e posteriores):** seu
  histórico de reprodução, pelo armazenamento que você escolher, como descrito
  na próxima seção.
- **Dispositivos Apple:** com o iCloud, seu progresso, suas avaliações, suas
  resenhas, seus favoritos e sua lista são sincronizados pelo seu banco de
  dados privado do iCloud. Contas vinculadas, logins salvos, seu login do
  TMDB e sua chave de API de legendas são sincronizados pelas Chaves do iCloud
  com seu iPhone, iPad, Mac e Apple Vision Pro. A Apple TV mantém suas
  próprias cópias. Os backups do próprio dispositivo incluem os dados do
  Edendale, como os de qualquer app.
- **Windows:** antes do Edendale 27.1, o Edendale copiava seu progresso e o
  estado dos seus títulos por meio da pasta `Apps/Edendale` no seu próprio
  OneDrive sempre que o OneDrive estava configurado no PC. Logins e contas
  nunca saíam do dispositivo. O Edendale 27.1 deixa de copiar: ele lê esses
  arquivos uma vez, os deixa onde estão e oferece essa pasta como local de
  sincronização. A sincronização continua desativada até você concordar.
- **Android:** o próprio backup do Android pode incluir sua biblioteca (com o
  e-mail ou o nome da conta a que pertence cada origem vinculada), seus dados
  de reprodução, seus ajustes e a opção “Adequado para público jovem”. Logins,
  chaves, tokens de conta, os ajustes de sincronização, o PIN dos pais e o
  restante do controle dos pais ficam fora dos backups e das transferências
  entre dispositivos.
- **Meta Quest:** se o backup na nuvem estiver ativado nas configurações do
  headset, o backup da Meta usa o do Android, com as mesmas exclusões. Remover
  o app do headset apaga tudo o que ele guardou ali. A Meta mantém os backups
  dela e restaura o mais recente se você instalar o Edendale de novo, até que
  você os apague no backup na nuvem das configurações do headset; veja
  [a ajuda da Meta sobre backup na nuvem](https://www.meta.com/help/quest/399621398228171/).
- **Sua conta do TMDB (opcional):** se você entrar no The Movie Database, o
  Edendale mantém seus favoritos, sua lista e suas avaliações sincronizados
  com essa conta. O progresso de reprodução nunca é enviado ao TMDB.

## Sincronização entre dispositivos

A partir do Edendale 27.1, **Ajustes → Sincronização** pode manter seu
histórico de reprodução igual em todos os seus dispositivos. Ela fica
desativada até você ativá-la e escolher um local. O Edendale continua sem
servidores e sem sistema de contas: seus dispositivos compartilham o
histórico apenas por um armazenamento que já é seu.

- **O que um arquivo de sincronização contém:** cada dispositivo grava um
  arquivo, `Edendale/<nome do espaço>/<ID do dispositivo>.json`, com seu
  progresso de reprodução e se você terminou cada título, suas avaliações,
  resenhas, favoritos e o estado da sua lista, cada um identificado pelo ID do
  TMDB do título e marcado com a hora da última alteração e o dispositivo que
  a fez; o nome do espaço; o nome e a plataforma do dispositivo; um ID
  aleatório criado para a sincronização; e a versão do Edendale. Ele nunca
  contém sua biblioteca, nomes ou caminhos de arquivos, logins, sua sessão do
  TMDB nem o controle dos pais.
- **Onde ele fica:** no local que você escolhe e que é seu: uma pasta do seu
  dispositivo (incluindo o iCloud Drive ou uma pasta oferecida por outro app
  de nuvem), a pasta do próprio Edendale no iCloud Drive nos dispositivos
  Apple, seu Google Drive, o OneDrive (na pasta de aplicativos dele,
  `Apps/Edendale`), o Dropbox ou seu próprio servidor WebDAV, SMB ou SFTP.
  Cada dispositivo lê ali os arquivos dos outros dispositivos e os combina no
  próprio dispositivo. Os arquivos são JSON simples e legível, então qualquer
  pessoa que consiga abrir essa pasta ou essa conta pode ler seu histórico de
  reprodução; o Edendale avisa isso quando você escolhe um local.
- **Como parar:** **Desativar** em Ajustes → Sincronização interrompe a
  sincronização naquele dispositivo e oferece apagar o arquivo dele; o
  histórico no dispositivo continua. Remover um dispositivo em Ajustes →
  Sincronização apaga o arquivo desse dispositivo. Limpar o progresso de um
  título, ou **Excluir histórico** no Android, o apaga no dispositivo e, com a
  sincronização ativada, nos seus outros dispositivos quando eles
  sincronizarem de novo. Depois disso, os arquivos de sincronização mantêm por
  até um ano uma marcação para cada título apagado, sem o progresso, para que
  uma cópia mais antiga não o traga de volta. Para remover tudo, desative a
  sincronização em todos os dispositivos e apague a pasta `Edendale` do seu
  armazenamento.
- **Configurar outro dispositivo:** **Configurar outro dispositivo** envia o
  local de sincronização e o login dele, e as outras contas e logins que você
  marcar, diretamente de um dispositivo seu para outro pela sua rede local. Um
  dispositivo mostra um código QR, que o outro escaneia ou abre como link, ou,
  quando os dois dispositivos oferecem isso, um código que você digita no
  outro. Os dois dispositivos o usam para combinar uma chave que criptografa a
  conexão, e o dispositivo que envia pede que você confirme o nome do outro
  antes de enviar qualquer coisa. Nada passa por nós nem por mais ninguém. No
  iPhone, no iPad, em celulares e tablets Android e no Meta Quest 3 e 3S, o
  Edendale pode escanear o código com a câmera: ele só pede acesso à câmera
  quando você escolhe escanear, lê o código no dispositivo e nunca guarda nem
  envia as imagens da câmera. Enquanto a tela de pareamento está aberta, o
  dispositivo anuncia o nome e a plataforma dele na sua rede local para que o
  outro possa encontrá-lo. Entre
  plataformas para as quais um login do Google não pode ser transferido, só
  são enviados o endereço de e-mail da Conta do Google, o nome do espaço e o
  ID da pasta de sincronização, e você entra no Google no novo dispositivo.
- **Um dispositivo que você remove** mantém o login que recebeu, e o
  histórico que já tem, até você revogar o acesso do Edendale no seu provedor
  de armazenamento ou trocar a senha do servidor.

## Serviços online que o Edendale usa

Cada serviço abaixo recebe o endereço IP do seu dispositivo, como em qualquer
conexão com a internet, além do seguinte:

- **[TMDB](https://www.themoviedb.org/privacy-policy)** (The Movie Database),
  para detalhes, imagens e classificações indicativas de filmes e séries: o
  título que o Edendale lê do nome de um arquivo, às vezes com o ano (nunca o
  nome completo, a pasta ou o próprio arquivo), o que você digita na busca e
  os IDs do TMDB dos títulos que você consulta ou cujos detalhes o Edendale
  busca. Se você entrar na sua conta, também a sua sessão do TMDB.
- **[Wyzie Subs](https://wyzie.io/privacy)**, só quando você busca legendas
  online: o ID do TMDB do título, os números da temporada e do episódio, os
  idiomas e formatos de legenda que o Edendale pede, se devem ser incluídas
  legendas para surdos e ensurdecidos, e uma chave de API: a sua, se você
  digitou uma, ou senão a do próprio Edendale.
- **[TheIntroDB](https://theintrodb.org/docs/privacy)**, só enquanto os
  Botões para Pular estiverem ativados (vêm desativados): o ID do TMDB do
  título, os números da temporada e do episódio e a duração do vídeo.
- **[YouTube](https://policies.google.com/privacy)**, só quando você escolhe
  assistir a um trailer. Em dispositivos Apple que não sejam a Apple TV, e em
  dispositivos Android que não sejam Meta Quest, o Edendale o reproduz no modo
  de privacidade aprimorada do YouTube (youtube-nocookie.com). Na Apple TV,
  ele abre o trailer no app do YouTube; no Windows, em youtube.com no seu
  navegador; e, no Meta Quest, no navegador ou no app do YouTube.
- **O armazenamento que você vincula**, e o que você escolhe para a
  sincronização, descritos na próxima seção.

## O armazenamento que você vincula

O Edendale reproduz vídeos de pastas do seu dispositivo e do armazenamento que
você vincula: servidores SMB, NFS, SFTP e WebDAV, armazenamento compatível com
S3, Google Drive, OneDrive e Dropbox. Os serviços disponíveis variam conforme
a plataforma. Cada conexão vai diretamente do seu dispositivo ao serviço que
você escolheu. Nada passa por um servidor operado por nós.

- **Login:** no Google Drive, OneDrive e Dropbox, você entra pela página do
  próprio provedor usando OAuth 2.0 com PKCE, então o Edendale nunca vê sua
  senha. Logins de servidores (nomes de usuário, senhas e chaves de acesso)
  são enviados apenas ao servidor a que pertencem.
- **Acesso limitado:** o Google recebe só `drive.file`, que abrange os vídeos
  que você escolhe no seletor de arquivos do próprio Google e os arquivos que
  o Edendale cria, e nada mais do seu Drive. Microsoft: `Files.Read`,
  `Files.ReadWrite.AppFolder`, `User.Read` e `offline_access`. Dropbox:
  `account_info.read`, `files.metadata.read`, `files.content.read` e
  `files.content.write`. O Edendale só lê seus vídeos: nunca os envia,
  altera, compartilha nem apaga, nem nada mais no seu armazenamento. Os únicos
  arquivos que ele grava ou apaga são os próprios arquivos de sincronização, e
  só se você ativar a sincronização: na pasta `Edendale` dele no Google
  Drive, no Dropbox ou no servidor ou na pasta que você escolheu, e na pasta
  de aplicativos do OneDrive.
- **O que o Edendale lê:** o ID, o endereço de e-mail e o nome da sua conta,
  para identificá-la e manter as origens dela separadas; os nomes, tamanhos,
  datas e durações dos arquivos e pastas nos locais que você explora e vincula
  ou, no Google Drive, dos vídeos que você escolhe; o conteúdo de um vídeo
  apenas enquanto você o assiste; e, se você ativar a sincronização, os
  arquivos de sincronização dos seus dispositivos.
- **O que o Edendale guarda:** os dados dos arquivos passam a fazer parte da
  sua biblioteca no dispositivo. Tokens de login e credenciais vão para o
  armazenamento protegido, como descrito acima. Os dados de vídeo ficam na
  memória durante a reprodução e nunca são salvos no disco.
- **TVs:** uma Apple TV pode receber uma conta ou login do seu iPhone ou iPad,
  e uma Android TV do Edendale em outro dispositivo Android, por uma conexão
  criptografada na sua rede local, só depois que você inicia a transferência
  na TV e a confirma no outro dispositivo. Enquanto uma Android TV espera,
  ela anuncia o nome dela na sua rede local. Em uma TV, o OneDrive também pode
  entrar com um código que você aprova em outro dispositivo. A partir do
  Edendale 27.1, qualquer dispositivo seu pode configurar outro dessa forma,
  como descrito em “Sincronização entre dispositivos”.

## Dados de usuário do Google

Quando você vincula o Google Drive, você escolhe vídeos no seletor de arquivos
do próprio Google, que abre quando você entra e sempre que você usa
**Escolher Mais Vídeos**. Se você entrar no Google Drive só para guardar ali
os arquivos de sincronização, o seletor não abre. O Edendale acessa:

- os vídeos que você escolhe, e nada mais do seu Google Drive (`drive.file`):
  o Edendale os lista, lê os nomes, tamanhos, datas e durações de vídeo deles
  e transmite os que você escolhe assistir. O Google compartilha suas escolhas
  entre os apps do Edendale, então um vídeo escolhido em um dispositivo também
  pode aparecer em outro conectado à mesma Conta do Google;
- se você escolher o Google Drive para a sincronização, a pasta `Edendale` e
  os arquivos de sincronização que o próprio Edendale cria nela, que ele lê,
  substitui e apaga como descrito em “Sincronização entre dispositivos”; e
- o ID de usuário do Google Drive, o endereço de e-mail e o nome da sua
  conta, que o Google Drive fornece com essa mesma permissão, para mostrar
  qual conta está vinculada e distinguir suas contas.

O Edendale usa esses dados apenas para oferecer sua origem do Google Drive
(listar os vídeos que você escolheu e reproduzi-los) e, se você quiser, para
manter seus arquivos de sincronização no seu Drive. Ele nunca envia, altera,
compartilha nem apaga seus vídeos ou qualquer outro arquivo do seu Drive.
Como em qualquer origem, o Edendale lê os nomes dos arquivos no seu
dispositivo para reconhecer filmes e episódios e envia ao TMDB apenas o
título reconhecido, às vezes com o ano, para buscar os detalhes.

Os dados ficam nos seus dispositivos e no seu próprio Drive: os dados dos
arquivos na sua biblioteca, e a conta vinculada (ID, endereço de e-mail, nome
e token de login) no armazenamento protegido, como descrito acima. Nos
dispositivos Apple, as Chaves do iCloud sincronizam a conta com seus outros
dispositivos Apple. Ela só chega a outro dispositivo quando você confirma uma
transferência a partir de um dos seus; entre plataformas para as quais um
login do Google não pode ser transferido, só são enviados o endereço de e-mail
e o ID da pasta de sincronização. Se você remover um vídeo da sua biblioteca,
o Edendale pode guardar o ID do arquivo só naquele dispositivo, para que o
vídeo não volte a ser listado. Os dados de usuário do Google nunca são
enviados para nós nem para qualquer servidor operado por nós, então nunca os
vemos nem os lemos. Eles nunca são vendidos, nunca são usados para publicidade
e nunca são usados para desenvolver, aprimorar ou treinar modelos de
inteligência artificial ou de aprendizado de máquina.

Para encerrar o acesso do Edendale, remova a origem (o que também remove os
arquivos dela da sua biblioteca) e finalize a sessão em **Ajustes → Contas**.
**Finalizar Sessão e Revogar Acesso**, ou **Finalizar Sessão** no Windows,
também revoga o acesso do Edendale no Google. Você pode removê-lo a qualquer
momento nas
[conexões de terceiros da sua Conta do Google](https://myaccount.google.com/connections).
Revogar o acesso o encerra em todos os dispositivos, incluindo as TVs, e o
Google esquece os vídeos que você escolheu. Os arquivos de sincronização que o
Edendale criou continuam no seu Drive até você apagá-los. Apagar o app apaga
tudo o que ele guardou naquele dispositivo. Um backup no Android ou no Meta
Quest pode manter uma cópia da sua biblioteca, incluindo o e-mail ou o nome da
conta de onde vêm os vídeos dela, mas nunca o token de login da conta.

O uso e a transferência, pelo Edendale, para qualquer outro app de
informações recebidas das APIs do Google seguirão a
[Google API Services User Data Policy](https://developers.google.com/terms/api-services-user-data-policy)
(Política de Dados do Usuário dos Serviços de API do Google), incluindo os
requisitos de uso limitado (Limited Use).

## Contas da Microsoft e do Dropbox

O OneDrive e o Dropbox funcionam de forma muito parecida: acesso de leitura às
pastas que você explora e vincula, usado só para listar e reproduzir seus
vídeos, e, se você escolher um deles para a sincronização, acesso de gravação
usado só para os arquivos de sincronização do próprio Edendale, na pasta de
aplicativos do OneDrive (`Apps/Edendale`) ou na pasta `Edendale` do seu
Dropbox. O Edendale nunca altera nem apaga mais nada ali. Os dados da conta e
os tokens de login ficam guardados só nos seus dispositivos. Finalize a
sessão em **Ajustes → Contas**. Nos dispositivos Apple e no Android,
**Finalizar Sessão e Revogar Acesso** também encerra o acesso do Edendale no
Dropbox. Você também pode
remover o Edendale dos
[apps com acesso à sua conta Microsoft](https://account.live.com/consent/Manage)
ou dos seus
[apps conectados do Dropbox](https://www.dropbox.com/account/connected_apps).
Os arquivos de sincronização continuam no seu armazenamento até você
apagá-los. Uma conta Microsoft corporativa ou de estudante pode ser gerenciada
pela sua organização.

## Este site

Este site é estático e hospedado no GitHub Pages. Ele não usa cookies, não
guarda nada no seu navegador, não tem formulários e não carrega analytics,
fontes nem scripts de outros sites. Ele escolhe um idioma com base nas
configurações do seu navegador sem guardar nada, e o idioma que você escolhe
fica apenas no endereço da página. O GitHub, como serviço de hospedagem,
recebe as informações comuns de cada solicitação, como o seu endereço IP;
veja a
[GitHub General Privacy Statement](https://docs.github.com/en/site-policy/privacy-policies/github-general-privacy-statement).
Os links que abrem o app Edendale são tratados no seu dispositivo.

## Crianças

O Edendale não coleta intencionalmente informações pessoais de ninguém,
incluindo crianças. Os apps não nos enviam nada, então não há nada para
coletarmos.

## Suas escolhas

Você pode ver, alterar ou apagar seus dados no app a qualquer momento: remover
uma origem, sair de uma conta, desativar a sincronização entre dispositivos, a
sincronização do iCloud ou o backup do seu dispositivo, apagar os arquivos de
sincronização (e, no Windows, as cópias antigas do OneDrive) do seu
armazenamento ou apagar o app. Como não temos nenhum dado pessoal seu, esses
controles são a forma de exercer seus direitos de acesso e exclusão. Os dados
mantidos pelos serviços acima seguem as políticas de privacidade deles.

## Alterações nesta política

Quando a forma como os apps tratam dados mudar, atualizaremos esta página e a
data no topo. Cada revisão fica pública no histórico do projeto no GitHub.

## Contato

Dúvidas sobre esta política ou sobre a privacidade no Edendale são
bem-vindas como uma issue em
[github.com/Ju-Long/Edendale/issues](https://github.com/Ju-Long/Edendale/issues).
