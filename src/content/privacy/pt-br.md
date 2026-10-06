---
updated: 2026-10-06
summary:
  - O Edendale não tem sistema de contas nem servidores próprios. Os apps não contêm analytics, publicidade nem rastreamento.
  - Sua biblioteca, seus ajustes e seus logins salvos ficam no seu dispositivo. O que é sincronizado passa por um serviço que você controla, como o iCloud ou o seu próprio OneDrive.
  - Google Drive, OneDrive e Dropbox recebem acesso somente leitura, são acessados diretamente do seu dispositivo e só servem para listar e reproduzir seus vídeos.
  - Detalhes de filmes, busca de legendas, botões para pular e trailers contatam os serviços indicados abaixo, apenas para a finalidade descrita.
---

## A quem esta política se aplica

Esta política abrange os apps do Edendale para dispositivos Apple (iPhone,
iPad, Mac, Apple TV e Apple Vision Pro), Android e Windows, e este site,
edendale.babasama.com. O Edendale é um projeto livre e de código aberto,
desenvolvido publicamente em
[github.com/Ju-Long/Edendale](https://github.com/Ju-Long/Edendale). “Nós” se
refere às pessoas que o desenvolvem.

## Não coletamos seus dados

O Edendale não tem sistema de contas e não opera servidores que recebam
informações dos apps. Os apps não contêm código de analytics, publicidade,
rastreamento nem relatórios de falhas. Suas informações nunca chegam até nós,
então não temos nada para vender, alugar ou compartilhar.

Se você permitir que seu dispositivo compartilhe diagnósticos com
desenvolvedores de apps, a loja em que você instalou o Edendale (Apple, Google
ou Microsoft) pode nos fornecer relatórios de falhas e estatísticas de uso
agregadas, de acordo com a política de privacidade dela. Usamos esses dados
apenas para corrigir problemas.

## O que fica no seu dispositivo

- **Sua biblioteca:** as pastas e origens que você adiciona; os nomes,
  tamanhos, datas e durações dos arquivos que o Edendale encontra nelas; e o
  filme ou episódio associado a cada arquivo.
- **Seus ajustes:** preferências de reprodução, áudio, imagem, legendas e
  controles, incluindo as escolhas lembradas para cada título.
- **As legendas que você baixa.**
- **Logins salvos e contas vinculadas:** senhas de servidores, chaves de
  acesso do S3 e tokens de login na nuvem, guardados no armazenamento
  protegido do sistema: as Chaves (Keychain) nos dispositivos Apple, um
  armazenamento criptografado com o Android Keystore e a Proteção de Dados do
  Windows (DPAPI). Tokens de acesso de curta duração ficam apenas na memória.

O Edendale lê os nomes dos arquivos no seu dispositivo para reconhecer filmes
e episódios antes de contatar qualquer serviço online, e nunca envia seus
vídeos para lugar nenhum.

## O que pode ser sincronizado, e onde

O Edendale só sincroniza por meio de serviços que você controla, e só quando
você os ativa:

- **Dispositivos Apple:** com o iCloud, seu progresso, suas avaliações, seus
  favoritos e sua lista são sincronizados pelo seu banco de dados privado do
  iCloud. Contas vinculadas e logins salvos são sincronizados pelas Chaves do
  iCloud com seu iPhone, iPad, Mac e Apple Vision Pro. A Apple TV mantém suas
  próprias cópias.
- **Windows:** se você ativar a replicação pelo OneDrive, seu progresso e o
  estado dos seus títulos são copiados por meio de uma pasta no seu próprio
  OneDrive. Logins e contas nunca saem do dispositivo.
- **Android:** o próprio backup do Android pode incluir sua biblioteca e seus
  dados de reprodução. Logins, chaves e tokens de conta ficam fora dos backups
  e das transferências entre dispositivos.
- **Sua conta do TMDB (opcional):** se você entrar no The Movie Database, o
  Edendale mantém seus favoritos, sua lista e suas avaliações sincronizados
  com essa conta. O progresso de reprodução nunca é enviado ao TMDB.

## Serviços online que o Edendale usa

Cada serviço abaixo recebe o endereço IP do seu dispositivo, como em qualquer
conexão com a internet, além do seguinte:

- **[TMDB](https://www.themoviedb.org/privacy-policy)** (The Movie Database),
  para detalhes e imagens de filmes e séries: o título e o ano que o Edendale
  lê do nome de um arquivo (nunca o nome completo, a pasta ou o próprio
  arquivo) e os IDs do TMDB dos títulos que você consulta. Se você entrar na
  sua conta, também a sua sessão do TMDB.
- **[Wyzie Subs](https://wyzie.io/privacy)**, só quando você busca legendas
  online: o ID do TMDB do título, os números da temporada e do episódio, os
  idiomas pedidos e a sua chave de API.
- **[TheIntroDB](https://theintrodb.org/docs/privacy)**, só enquanto os
  Botões para Pular estiverem ativados (vêm desativados): o ID do TMDB do
  título, os números da temporada e do episódio e a duração do vídeo.
- **[YouTube](https://policies.google.com/privacy)**, só quando você escolhe
  assistir a um trailer. Em dispositivos Apple e Android, o Edendale o
  reproduz no modo de privacidade aprimorada do YouTube
  (youtube-nocookie.com). No Windows, ele abre o trailer em youtube.com no seu
  navegador.
- **O armazenamento que você vincula**, descrito na próxima seção.

## O armazenamento que você vincula

O Edendale reproduz vídeos de pastas do seu dispositivo e do armazenamento que
você vincula: servidores SMB, NFS, SFTP e WebDAV, armazenamento compatível com
S3, Google Drive, OneDrive e Dropbox. Os serviços disponíveis variam conforme
a plataforma; por enquanto, o Google Drive está disponível nos dispositivos
Apple. Cada conexão vai diretamente do seu dispositivo ao serviço que você
escolheu. Nada passa por um servidor operado por nós.

- **Login:** no Google Drive, OneDrive e Dropbox, você entra pela página do
  próprio provedor usando OAuth 2.0 com PKCE, então o Edendale nunca vê sua
  senha. Logins de servidores (nomes de usuário, senhas e chaves de acesso)
  são enviados apenas ao servidor a que pertencem.
- **Acesso somente leitura:** o Edendale solicita permissões somente leitura.
  Google: `openid`, `email` e `drive.readonly`. Microsoft: `Files.Read`,
  `User.Read` e `offline_access`. Dropbox: `account_info.read`,
  `files.metadata.read` e `files.content.read`. O Edendale não pode criar,
  alterar, compartilhar nem apagar nada no seu armazenamento.
- **O que o Edendale lê:** o ID e o endereço de e-mail da sua conta, para
  identificá-la e manter as origens dela separadas; os nomes, tamanhos, datas
  e durações dos arquivos e pastas nos locais que você explora e vincula; e o
  conteúdo de um vídeo apenas enquanto você o assiste.
- **O que o Edendale guarda:** os dados dos arquivos passam a fazer parte da
  sua biblioteca no dispositivo. Tokens de login e credenciais vão para o
  armazenamento protegido, como descrito acima. Os dados de vídeo ficam na
  memória durante a reprodução e nunca são salvos no disco.
- **TVs:** uma Apple TV pode receber uma conta ou login do seu iPhone ou iPad
  por uma conexão criptografada na sua rede local, só depois que você inicia
  a transferência na TV e a confirma no celular ou tablet. Em uma TV, o
  OneDrive também pode entrar com um código que você aprova em outro
  dispositivo.

## Dados de usuário do Google

Quando você vincula o Google Drive, o Edendale acessa:

- o ID exclusivo e o endereço de e-mail da sua Conta do Google (`openid` e
  `email`), para mostrar qual conta está vinculada e distinguir suas contas;
  e
- os arquivos e pastas do seu Google Drive (`drive.readonly`): o Edendale
  lista as pastas que você explora e vincula, lê os nomes, tamanhos, datas e
  durações de vídeo dos arquivos que elas contêm e transmite os vídeos que
  você escolhe assistir.

O Edendale usa esses dados apenas para oferecer sua origem do Google Drive:
escolher uma pasta, listar os vídeos dela e reproduzi-los. Como em qualquer
origem, o Edendale lê os nomes dos arquivos no seu dispositivo para
reconhecer filmes e episódios e envia ao TMDB apenas o título e o ano
reconhecidos para buscar os detalhes.

Os dados ficam nos seus dispositivos: os dados dos arquivos na sua
biblioteca, e a conta vinculada (ID, endereço de e-mail e token de login) nas
Chaves, que as Chaves do iCloud sincronizam com seus outros dispositivos
Apple. Ela só chega a uma Apple TV quando você confirma uma transferência a
partir do seu iPhone ou iPad. Os dados de usuário do Google nunca são
enviados para nós nem para qualquer servidor operado por nós, então nunca os
vemos nem os lemos. Eles nunca são vendidos, nunca são usados para
publicidade e nunca são usados para desenvolver, aprimorar ou treinar modelos
de inteligência artificial ou de aprendizado de máquina.

Para encerrar o acesso do Edendale, remova a origem (o que também remove os
arquivos dela da sua biblioteca) e finalize a sessão em **Ajustes → Contas**.
**Finalizar Sessão e Revogar Acesso** também revoga o acesso do Edendale no
Google. Você pode removê-lo a qualquer momento nas
[conexões de terceiros da sua Conta do Google](https://myaccount.google.com/connections).
Apagar o app apaga tudo o que ele guardou naquele dispositivo.

O uso e a transferência, pelo Edendale, para qualquer outro app de
informações recebidas das APIs do Google seguirão a
[Google API Services User Data Policy](https://developers.google.com/terms/api-services-user-data-policy)
(Política de Dados do Usuário dos Serviços de API do Google), incluindo os
requisitos de uso limitado (Limited Use).

## Contas da Microsoft e do Dropbox

O OneDrive e o Dropbox funcionam da mesma forma: acesso somente leitura,
usado só para listar e reproduzir seus vídeos e guardado só nos seus
dispositivos. Finalize a sessão em **Ajustes → Contas**. No Dropbox,
**Finalizar Sessão e Revogar Acesso** também encerra o acesso do Edendale no
Dropbox. Você também pode remover o Edendale dos
[apps com acesso à sua conta Microsoft](https://account.live.com/consent/Manage)
ou dos seus
[apps conectados do Dropbox](https://www.dropbox.com/account/connected_apps).
Uma conta Microsoft corporativa ou de estudante pode ser gerenciada pela sua
organização.

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
uma origem, sair de uma conta, desativar a sincronização do iCloud ou a
replicação pelo OneDrive, ou apagar o app. Como não temos nenhum dado pessoal
seu, esses controles são a forma de exercer seus direitos de acesso e
exclusão. Os dados mantidos pelos serviços acima seguem as políticas de
privacidade deles.

## Alterações nesta política

Quando a forma como os apps tratam dados mudar, atualizaremos esta página e a
data no topo. Cada revisão fica pública no histórico do projeto no GitHub.

## Contato

Dúvidas sobre esta política ou sobre a privacidade no Edendale são
bem-vindas como uma issue em
[github.com/Ju-Long/Edendale/issues](https://github.com/Ju-Long/Edendale/issues).
