# TV Video Browser

Navegador para Android TV focado em **reprodução de vídeo**, com interface limpa
controlada 100% pelo controle remoto (D-pad) e bloqueio de anúncios/malware a
nível de rede. Testado para funcionar bem em hardware limitado, como a
**Xiaomi Mi Box (3ª geração, 2GB RAM / 32GB)**.

## Instalação (APK compilado automaticamente)

Toda vez que este repositório recebe um push, o GitHub Actions compila o
projeto e publica o APK assinado na aba **[Releases](../../releases/latest)**
do repositório. O link direto e estável de download é:

```
https://github.com/zzp4yv/browser/releases/latest/download/TVBrowser-android-tv.apk
```

Como instalar na Mi Box (ou qualquer Android TV) sem precisar de PC:

1. Na Mi Box, instale o app **"Downloader"** (da AFTVnews) pela Play Store.
2. Abra o Downloader e cole a URL acima para baixar o APK.
3. Ao terminar o download, escolha "Instalar". Se pedir, habilite
   "Fontes desconhecidas" para o app Downloader/Arquivos.
4. Pronto — o ícone "TV Video Browser" aparecerá na tela inicial.

Alternativa via ADB (a partir de um PC na mesma rede):

```
adb connect <ip-da-mibox>:5555
adb install -r TVBrowser-android-tv.apk
```

## Controles do remoto

Navegar em páginas comuns (fora da reprodução em tela cheia) usa um
**cursor de ponteiro na tela**, em vez de depender da navegação por foco de
cada site — muitos sites (grades de vídeo, players customizados) simplesmente
não têm foco navegável por teclado, e mesmo quando têm, o foco fica
invisível. O cursor sempre aparece na tela, então você sempre sabe onde está:

| Botão | Ação (navegação normal) | Ação (vídeo em tela cheia) |
|---|---|---|
| ◀ ▲ ▶ ▼ | Move o cursor na tela (acelera se segurar) | Avança/retrocede 10s (◀ ▶) |
| OK / Enter | Clica na posição do cursor | Reproduz / pausa |
| Botões dedicados de mídia (▶⏸, ⏪, ⏩) | Funcionam em qualquer tela |  |
| Menu | Mostra/esconde a barra (voltar, avançar, recarregar, início, favoritar, versão desktop, endereço) |  |
| Voltar | Volta página → volta à tela inicial | Sai da tela cheia |

Ao chegar perto da borda da tela, o cursor "empurra" o conteúdo (a página
rola) em vez de sumir de vista — assim dá para alcançar qualquer parte de
uma página longa sem perder a posição do cursor.

Na tela inicial, use o D-pad para navegar entre os atalhos, "+" para adicionar
um novo site e "🔎 Abrir endereço" para digitar uma URL ou termo de busca.
Pressione e segure OK sobre um atalho adicionado por você para removê-lo.

## Versão desktop

Por padrão, o navegador se identifica para os sites como um Chrome de
computador (não como TV/celular), para receber a versão completa (desktop)
do site em vez de uma versão mobile simplificada — isso costuma ter mais
opções de player e menos limitações de vídeo. Pressione **Menu** e depois
**"Versão desktop"** para alternar entre desktop/padrão a qualquer momento;
a escolha fica salva e vale para todas as próximas páginas e reaberturas do
app, até você alternar de novo.

## Bloqueio de anúncios

O bloqueio é feito **por domínio**, na camada de rede do WebView
(`shouldInterceptRequest`), e não por padrões de URL — isso evita cortar
partes de um vídeo legítimo por engano:

- Lista base embutida no APK (funciona offline): combinação das listas
  públicas [`anudeepND/blacklist`](https://github.com/anudeepND/blacklist)
  (~42 mil domínios de anúncios/rastreadores, atualizada diariamente) e
  [`anudeepND/whitelist`](https://github.com/anudeepND/whitelist) (domínios que
  nunca devem ser bloqueados).
- Uma lista de exceção adicional (`critical_allowlist.txt`) garante que CDNs
  de vídeo essenciais (YouTube/`googlevideo.com`, Netflix, Vimeo, Twitch,
  Globoplay, Prime Video, Disney+, HBO Max, etc.) nunca sejam bloqueadas,
  mesmo que apareçam por engano em alguma lista pública.
- A cada abertura do app, ele tenta atualizar a lista de anúncios direto do
  GitHub (`anudeepND/blacklist`) em segundo plano. Se não houver internet ou a
  busca falhar, a lista embutida continua funcionando normalmente.
- Pop-ups/pop-unders (`window.open`, `target="_blank"`) são resolvidos e só são
  seguidos se o domínio de destino não estiver na lista de bloqueio — ou seja,
  a maioria dos pop-ups de anúncio é descartada silenciosamente.

**Limitação conhecida:** em alguns sites (principalmente YouTube), os anúncios
em vídeo são entregues pelo mesmo CDN do conteúdo (`googlevideo.com`), então
bloqueio por domínio não consegue removê-los sem também bloquear o vídeo
principal — por isso esse domínio está na lista de exceção. Bloqueio de
anúncios de vídeo embutidos no próprio stream exigiria um bloqueador de
conteúdo em nível de player, fora do escopo deste app.

## Por que roda bem na Mi Box 3 (2GB RAM)

- Sem Jetpack Compose, sem bibliotecas pesadas de UI: só Views nativas +
  RecyclerView.
- Uma única instância de WebView reaproveitada, com
  `setRendererPriorityPolicy(RENDERER_PRIORITY_IMPORTANT)` para priorizar o
  processo de renderização durante a reprodução.
- Lista de bloqueio carregada como `HashSet` (busca O(1) por domínio) em uma
  thread de fundo, para não atrasar a inicialização.
- `minifyEnabled`/`shrinkResources` habilitados no build de release para gerar
  um APK pequeno.
- `android:largeHeap="false"` e `hardwareAccelerated="true"` para manter o
  processo dentro do limite de memória padrão do sistema, usando aceleração
  de GPU para decodificação/composição de vídeo.

## Estrutura do projeto

```
app/src/main/java/com/tvbrowser/app/
├── TvBrowserApp.kt          # Application: inicializa o AdBlockManager
├── MainActivity.kt          # Tela inicial (grade de atalhos)
├── BrowserActivity.kt       # WebView em tela cheia + controles de remoto
├── adblock/AdBlockManager.kt
├── model/Bookmark.kt, BookmarkStore.kt
└── ui/BookmarkAdapter.kt

app/src/main/assets/adblock/
├── adservers.txt            # lista de bloqueio (anudeepND/blacklist)
├── whitelist.txt            # lista de exceção (anudeepND/whitelist)
└── critical_allowlist.txt   # CDNs de vídeo que nunca devem ser bloqueados
```

## Compilar localmente

```
./gradlew assembleRelease
# APK gerado em app/build/outputs/apk/release/app-release.apk
```

Requer JDK 17+ e o Android SDK (`ANDROID_HOME`/`ANDROID_SDK_ROOT` configurado,
com a Android SDK Platform e Build-Tools compatíveis com `compileSdk = 34`).

### Sobre a assinatura do APK

Este repositório inclui `app/release.keystore.jks`, uma chave **não secreta**,
gerada apenas para manter o mesmo assinador entre builds (permitindo
atualizar o app sem desinstalar). Ela não protege dados de usuário nem é uma
chave de publicação na Play Store. Se for distribuir este app fora do
GitHub/sideload, gere sua própria keystore privada e substitua a
configuração em `app/build.gradle.kts`.
