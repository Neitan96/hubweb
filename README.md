# HubWeb

Launcher de URLs para Android (Kotlin + Compose). Lista de sistemas vinda de um JSON remoto, cada um abre num WebView restrito.

## Permissões
O Android não pede permissões na instalação. O HubWeb pede todas na **primeira abertura**, de uma vez: localização (telemetria), câmera (sistemas que usam câmera) e, no Android 9 ou anterior, armazenamento (downloads). Não repete a cada início. O que o usuário negar volta a ser pedido sob demanda, quando o recurso for usado (se ele marcar "não perguntar de novo", só pelas configurações do Android: Apps → HubWeb → Permissões).

## Configuração gerenciada (MDM)
Com um MDM do Android Enterprise (Managed Google Play), o aparelho já pode sair configurado. O HubWeb declara duas chaves de configuração gerenciada (`res/xml/app_restrictions.xml`), que o console do MDM mostra como um formulário do app:

| Chave | Tipo | O que faz |
|---|---|---|
| `configUrl` | texto | URL (http ou https) do JSON com a lista de sistemas |
| `token` | texto | Token Bearer da lista (enviado só por HTTPS); opcional |

- Quando o MDM define a `configUrl`, ela **vale no lugar da digitada** nas Configurações, e o campo fica travado (o `token`, igual). Sem valor no MDM, vale o que o usuário digitou, como sempre.
- O app lê a configuração ao abrir e **quando o MDM a altera** (sem reabrir): ao mudar, ele busca a lista na hora.
- O aparelho que já recebe a URL pelo MDM abre direto no hub, sem passar pelo primeiro setup. A senha de administração continua vindo da própria lista (`senhaSha256`).
- **Como cadastrar:** no console do MDM, abra o HubWeb (app privado do Managed Google Play, ou o APK enviado pelo MDM) e use a configuração gerenciada de apps; os campos "URL da lista (JSON)" e "Token da lista" aparecem sozinhos. Os nomes exatos das chaves são `configUrl` e `token`.
- **Device owner:** se o MDM é o device owner do aparelho, o HubWeb não pode ser (o kiosk que trava de verdade vem do MDM). Nesses aparelhos, ponha `atualizacao.app.ativo: false` na lista e deixe o MDM atualizar o app.

## Documentação dentro do app
Em **Configurações** (precisa da senha de administração) há os botões **Documentação do JSON e do app** e **Exemplo de JSON**. Eles mostram este README e o `exemplo-apps.json`, sem precisar de internet; o exemplo tem um botão para copiar. Os dois arquivos são copiados do projeto para o app a cada build, então o app mostra sempre o texto do repositório.

## Primeiro uso
1. Abra o app. Sem senha ainda, ele abre direto nas **Configurações**: informe a **URL do JSON** (e o token, se houver) e salve. Com MDM, a URL e o token podem vir da **configuração gerenciada** (veja a seção acima) e este passo some.
2. A lista é buscada em segundo plano, conforme a regra `atualizacao.lista` do JSON (padrão: ao abrir o app, no mínimo a cada 60 s). Se a URL cair, vale o último JSON válido em cache.
3. A senha de administração **vem do JSON** (raiz): `senhaSha256` (SHA-256 em hex, recomendado) ou `senha` (texto). O app guarda só o hash, para valer offline. Depois que a primeira lista com senha chega, as configurações passam a exigi-la. Se uma lista vier sem senha, a última continua valendo. Não há troca de senha no app: para mudar, altere o JSON. 5 erros bloqueiam por 5 min.
   - Sem senha no JSON, as configurações ficam abertas a qualquer um.
   - O hash de uma senha curta pode ser descoberto por força bruta; use uma senha longa e HTTPS com token.
   - Se o seu servidor monta o JSON na hora, guarde a senha como segredo no servidor e responda só com o hash.

## Onde hospedar o JSON e receber os envios
O HubWeb só precisa de endereços HTTP(S); como servi-los é escolha sua:

- **Lista de sistemas** (`GET`): qualquer arquivo estático (servidor web, bucket, repositório) ou um serviço que monte o JSON na hora (por exemplo, juntando um arquivo por sistema). Aceita um token no cabeçalho `Authorization: Bearer ...`.
- **Dicas** (`GET`): opcional, mesmo formato de servidor. Veja "Dicas".
- **Telemetria e eventos** (`POST`): opcionais; um único endpoint pode receber tudo. Veja "Payloads".
- Quem monta o JSON dinamicamente deve tirar as chaves `_doc` antes de responder e nunca devolver senha em texto: gere o hash com `echo -n "minha-senha" | sha256sum` (Linux/macOS) ou `Get-FileHash` / `[BitConverter]` no PowerShell.

## Validar os exemplos
O script `tools\validar-chaves.ps1` confere que `exemplo-apps.json` e `exemplo-dicas.json` são JSON válido, que trazem **todas** as chaves que o app lê (e nenhuma que ele não lê) e que toda chave tem a sua `_doc`. Rode depois de mudar o que o app aceita (e atualize a lista de chaves no começo do script):

```
powershell -ExecutionPolicy Bypass -File tools\validar-chaves.ps1
```

## Documentação dentro do próprio JSON (chaves `_doc`)
Os exemplos (`exemplo-apps.json` e `exemplo-dicas.json`) são **autodocumentados**: ao lado de cada chave existe a chave `<nome>_doc` com a explicação (o significado, os valores aceitos e o padrão). Exemplo:

```json
"permitirCamera": true,
"permitirCamera_doc": "true libera a câmera para a própria página (padrão false)."
```

- **O app ignora toda chave terminada em `_doc`** (e a chave `_doc`, que documenta o objeto que a contém). Dá para deixá-las no JSON de produção sem efeito algum, mas elas aumentam o arquivo: com muitos aparelhos, **tire-as antes de responder**. Se o seu servidor monta a resposta dinamicamente, remova essas chaves nela.
- A documentação de uma chave aparece na **primeira vez** em que ela é usada no arquivo; as repetições, nos itens seguintes de `apps`, não repetem o texto.
- Uma chave `_doc` **sem** a chave correspondente documenta uma opção que o exemplo não liga de propósito (por exemplo `modoKiosk_doc`, `senhaSha256_doc`, `icone_doc`), para quem copiar o arquivo inteiro não forçar o kiosk nem trocar a senha.
- Copie o exemplo e apague o que não usar. A aba **Exemplo de JSON** das Configurações mostra o arquivo com todas as explicações.

## Formato do JSON
Veja `exemplo-apps.json`. A raiz pode ser `{"apps":[...]}` ou direto um array.

Na raiz (só no formato objeto) também vale `fundoUrl`: URL de uma imagem (PNG/JPG/WebP, até 15 MB) usada como plano de fundo do launcher. Ela é centralizada, reduzida só se for maior que a tela (nunca ampliada), baixada uma vez e guardada no aparelho; só baixa de novo quando a URL muda. Sem o campo, o fundo é removido. O token só é enviado se a imagem estiver no mesmo servidor da lista, por HTTPS.

`modoKiosk` (raiz): `true` ou `false` **obriga** o modo kiosk e trava a chave nas configurações do app. Ausente, `null` ou vazio devolve a escolha para o app. Vale o da última lista recebida (fica em cache se a URL cair) e é aplicado assim que a lista chega.

Campos de cada item de `apps`:

| Campo | Obrigatório | Padrão | Descrição |
|---|---|---|---|
| `nome` | sim | | Nome exibido |
| `urlInicial` | sim | | Página inicial (botão "início" volta para ela) |
| `urlPermitida` | não | `host-da-urlInicial/*` | Glob (`*` e `?`) contra `host/caminho`; string (separada por vírgula) ou array. Qualquer navegação fora é bloqueada |
| `icone` | não | inicial do nome | Imagem em base64 (PNG/JPG), com ou sem `data:...;base64,` |
| `permitirCopiarColar` | não | `false` | Libera seleção, copiar, recortar e colar |
| `permitirHttp` | não | `false` | Aceita `http://` (senão só `https://`) |
| `ordem` | não | 0 | Ordenação dentro do grupo |
| `grupo` | não | | Agrupa com cabeçalho no launcher |
| `orientacao` | não | `auto` | `auto`, `retrato` ou `paisagem` |
| `userAgent` | não | padrão do WebView | User-Agent customizado |
| `zoom` | não | 100 | Zoom do texto em % (50–300) |
| `permitirCamera` | não | `false` | Câmera para o site (leitura de código por câmera) |
| `permitirUpload` | não | `false` | Seletor de arquivos |
| `permitirDownload` | não | `false` | Downloads |
| `hub` | não | `true` | Mostra o item como cartão no launcher |
| `flutuante` | não | `false` | Também (ou só) um botão flutuante com popup (veja "Botões flutuantes") |
| `flutuanteObrigatorio` | não | `false` | Com `flutuante`: `true` trava o botão ligado; `false` deixa o usuário escolher |
| `flutuanteInicial` | não | `false` | Com `flutuante` opcional: o botão já vem ligado na primeira vez que o item aparece na lista (`true`) ou desligado (`false`) |
| `flutuanteTravado` | não | `false` | Com `flutuante`: `true` **trava a posição** do botão: o usuário não consegue arrastá-lo e ele fica em `flutuantePosicao` |
| `flutuantePosicao` | não | `"inferior-direita"` | Onde o botão travado fica: um canto (`"inferior-direita"`, `"inferior-esquerda"`, `"superior-direita"`, `"superior-esquerda"`, `"meio-direita"`, `"meio-esquerda"`) ou `{ "x": 0.9, "y": 0.8 }` (fração da área livre da tela, de 0 a 1). Só vale com `flutuanteTravado` |
| `barra` | não | tudo visível | Objeto com `visivel`, `voltar`, `atualizar` e `inicio`: barra já aberta ou oculta e quais botões ela tem (veja "Personalização do hub e da barra dos sistemas") |
| `leitorCamera` | não | `false` | Mostra na barra do item um botão para ler código de barras/QR pela câmera (veja "Leitor de código pela câmera") |
| `leitorSufixo` | não | `"enter"` | O que enviar depois do código: `"enter"`, `"tab"` ou `""` (nada) |
| `recarregarAoMudar` | não | `true` | Se a `urlInicial` tem variáveis (`{usuario}`, `{ssid}`, `{nome}`, `{versaoLista}`...), volta à `urlInicial` quando o valor de alguma delas muda. Variáveis que mudam o tempo todo (`bateria`, `carregando`, `sinalDbm`, `ramLivreMb`, `armazenamentoLivreMb`, `hora`, `data`, `lat`, `lon`, `sistemaAnterior`) são ignoradas na comparação. Verifica a cada 15 s. `false` desliga |

## Configurações do app
- URL do JSON e token (Bearer, enviado só por HTTPS)
- Timeout de inatividade e quantidade de sistemas abertos: **vêm da lista** (veja "Inatividade e sistemas abertos"); as Configurações só os mostram, junto com a RAM detectada
- Modo kiosk (padrão desligado)
- Alterar senha / Encerrar o HubWeb

## Lista por aparelho (mesma URL)
A **lista** e as **dicas** são buscadas com **POST**, e o corpo traz os dados do aparelho, com os mesmos nomes e valores da chave `dispositivo` da telemetria:

```json
{ "dispositivo": { "nome": "COLETOR-07", "id": "9f3a...", "modelo": "Zebra TC52", "android": "11", "app": "1.0.7" } }
```

- **`nome`**, **`id`** (identificador estável do aparelho), **`modelo`**, **`android`** (versão) e **`app`** (versão do HubWeb). Vai com `Content-Type: application/json` e o `Authorization: Bearer` de sempre.
- O servidor pode usá-los para devolver uma lista (e dicas) diferente por aparelho, na mesma URL. Quem não os usa devolve a mesma lista para todos.
- **O endereço da lista e o das dicas precisam aceitar POST** (e a resposta continua sendo o JSON). Um servidor que só aceita GET devolve erro, e o aparelho fica com a última lista que já tem. Atenção a redirecionamentos (301/302): o Android os refaz como GET.
- Os dados vão ao servidor que você configurou; o `id` e o `nome` identificam o aparelho, então use HTTPS.
- A imagem de fundo e o logo continuam sendo baixados com GET.

## Abrir um sistema ao entrar no app
O bloco opcional `abrirAoEntrar` abre um sistema sozinho, em vez de parar no hub:

```json
"abrirAoEntrar": { "sistema": "Webmail", "url": "https://mail.exemplo.com/caixa-de-entrada", "quando": "iniciar" }
```

- **`sistema`**: o `nome` de um item de `apps`. Também vale só o nome: `"abrirAoEntrar": "Webmail"`. Nome que não existe na lista: nada é aberto.
- **`url`** (opcional): a página em que o sistema abre, no lugar da `urlInicial`. Aceita as mesmas variáveis da `urlInicial` e só vale se casar com o `urlPermitida` do sistema; senão abre na `urlInicial`.
- **`quando`**: `"iniciar"` (padrão) abre só quando o app é aberto; `"sempre"` abre toda vez que o app volta ao primeiro plano.
- **Só age no hub:** não tira o usuário das Configurações nem de um sistema que ele já está usando. Depois que ele volta ao hub (Voltar ou timeout de inatividade), o sistema não abre de novo até a próxima entrada.
- Na primeira abertura sem lista em cache, abre assim que a lista chega. Sistemas com crachá obrigatório pedem o crachá, como ao abrir pelo hub.

## Trocar a URL ou o token da lista
Dois campos opcionais na raiz do JSON mudam a configuração guardada nos aparelhos, sem mexer em cada um:

```json
"novaConfigUrl": "https://exemplo.com/hubweb/apps-novo.json",
"novoToken": "token-novo"
```

- **`novaConfigUrl`**: nova URL (http ou https) da lista. **`novoToken`**: novo token Bearer (`""` apaga o token). Pode vir um só ou os dois.
- **Antes de adotar, o app confere:** busca a lista com a URL e o token novos; só se ela abrir e for um JSON válido os valores são gravados (e essa lista passa a valer). Se falhar, o aparelho continua com os atuais e tenta de novo na próxima busca. Assim um valor errado não deixa o aparelho sem acesso.
- **Como migrar:** deixe o campo na lista antiga até todos os aparelhos buscarem; depois retire. Aparelho que ficar desligado nesse período precisa de acesso à lista antiga ao ligar.
- **Não vale** para URL ou token definidos pelo MDM (configuração gerenciada): esses mandam e só mudam no MDM.

## Build da empresa (URL e token embutidos)
Para um APK que já sai configurado, sem MDM: copie `empresa.properties.example` para `empresa.properties` (fica fora do git), preencha `configUrl`, `token` e `sufixo`, e compile com `-Pempresa`:

```
gradlew assembleRelease -Pempresa
```

- O APK sai como `HubWeb <versão> <sufixo>.apk`. **Na primeira abertura**, o app grava a URL e o token embutidos nas Configurações, como se tivessem sido digitados. Depois disso, valem as Configurações (e o que a lista mandar por `novaConfigUrl`/`novoToken`), e o MDM, se houver, continua mandando.
- A **autoatualização pelo GitHub continua ligada**: o APK público instala por cima e os dados salvos permanecem (mesmo pacote e mesma assinatura). Por isso o aparelho precisa abrir o APK da empresa pelo menos uma vez antes de atualizar. Só desinstalar o app ou limpar os dados apaga a URL e o token.
- O token fica dentro do APK da empresa e pode ser extraído por quem tiver o arquivo: use um token restrito e não publique esse APK.

## Inatividade e sistemas abertos (por RAM)
Dois limites vêm da lista, na raiz do JSON:

```json
"timeoutInatividadeMinutos": 30,
"sistemasAbertos": [
  { "ramMinimaMb": 0,    "quantidade": 1 },
  { "ramMinimaMb": 1800, "quantidade": 2 },
  { "ramMinimaMb": 3500, "quantidade": 4 }
]
```

- **`timeoutInatividadeMinutos`** (0 a 1440, padrão 30; 0 desliga): minutos sem toque nem tecla (o leitor em modo teclado também conta) até o app voltar ao launcher. Também desliga os usuários por crachá.
- **`sistemasAbertos`**: quantos sistemas ficam abertos ao mesmo tempo (1 a 6; padrão 2 se a chave não existir). É uma lista de faixas: o app lê a **RAM total** do aparelho e usa a faixa de **maior `ramMinimaMb`** que ele atinge. Um aparelho com menos RAM que todas as faixas usa 1. Também aceita só um número (ex.: `"sistemasAbertos": 2`) para todos.
- **Folga na RAM:** o Android informa a RAM um pouco abaixo da nominal (4 GB aparecem como cerca de 3700 MB; 3 GB, cerca de 2800 MB). Escolha os `ramMinimaMb` com folga. As **Configurações** mostram a RAM detectada e a quantidade em uso.
- **Quando vale:** quando a lista é aplicada (e na lista em cache ao abrir o app). Se a quantidade diminuir, os sistemas que passam do limite são fechados na próxima abertura de um sistema, o menos recente primeiro.
- Antes, estes dois valores eram campos das Configurações do app; agora só a lista os define.

## Dados do aparelho na URL inicial
A `urlInicial` (no hub e no botão flutuante) aceita variáveis que o app troca por valores do aparelho, já codificados para URL, na abertura e também em atualizar/início:

`{nome}` · `{ip}` (IPv4 local; vazio se sem rede) · `{modelo}` · `{android}` · `{app}` (versão do HubWeb) · `{id}` (Android ID)

**Variáveis extras** (só na `urlInicial`; o rodapé não as aceita). O que o app não souber vira vazio, e um nome desconhecido fica como escrito:

| Variável | Valor |
|---|---|
| `{usuario}` | Login atual detectado **neste** sistema (com várias detecções, o mais recente) |
| `{usuario:Nome do sistema}` | Login atual de **outro** sistema, pelo `nome` dele em `apps` |
| `{usuarioDesde}` / `{usuarioDesde:Nome do sistema}` | Quando esse login foi detectado (UTC, ISO 8601) |
| `{fabricante}` | Fabricante do aparelho |
| `{bateria}` / `{carregando}` | Nível (0 a 100) / `true` ou `false` |
| `{ssid}` | Nome da rede Wi-Fi (precisa da permissão de localização) |
| `{redeTipo}` / `{sinalDbm}` | `wifi`, `celular`, `ethernet`, `outra` ou `nenhuma` / força do sinal |
| `{ramLivreMb}` / `{armazenamentoLivreMb}` | Memória livre / espaço livre no armazenamento, em MB |
| `{versaoLista}` | A chave `versao` do JSON da lista |
| `{sistemaAnterior}` | O sistema aberto antes deste |
| `{hora}` / `{data}` | `HH:mm` / `AAAA-MM-DD`, no horário do aparelho |
| `{deviceOwner}` / `{kiosk}` | `true` ou `false` |
| `{lat}` / `{lon}` | Última posição conhecida (graus decimais; precisa da permissão de localização) |

Exemplo com o login de outro sistema: `"urlInicial": "https://wms.exemplo.com/entrada?operador={usuario:Webmail}&aparelho={nome}"`.

Cuidados: os valores são os do momento em que a página inicial é carregada (abertura do sistema, atualizar quando há erro ou o botão Início); um sistema que já está aberto na memória não recarrega só por ser aberto de novo. Antes de o usuário entrar, `{usuario}` vai vazio. Tudo na URL aparece em logs de servidor e proxy: login, rede e posição identificam pessoas, então use HTTPS e só o que o sistema precisa.

Exemplo: `"urlInicial": "https://app.exemplo.com/painel?dispositivo={nome}&ip={ip}"` → `...?dispositivo=TABLET%2007&ip=192.168.0.15`. Só vale na primeira carga: se a página navegar para outro endereço, os parâmetros não vão junto. O valor é informado pelo aparelho e qualquer pessoa pode forjá-lo abrindo o link num navegador; serve para identificar e registrar, não para autenticar. O `urlPermitida` não considera a query string.

## Telemetria (POST periódico)
Objeto `telemetria` na raiz do JSON. Enquanto o app está **aberto em primeiro plano** (entre abrir e sair/ir para outro app), ele envia um POST JSON a cada `frequenciaSegundos`. **Ao entrar no app (abrir ou voltar a ele) já envia uma telemetria na hora**, sem esperar o primeiro intervalo, com a última localização conhecida do aparelho. Entradas seguidas em menos de 10 s (por exemplo, ao voltar do leitor de código ou de um diálogo de permissão) não repetem o envio. Na primeira abertura, sem lista em cache, o envio sai assim que a lista chega. Não envia em segundo plano nem com a tela bloqueada.

```json
"telemetria": { "url": "https://.../telemetria", "frequenciaSegundos": 60, "token": "opcional", "localizacao": true }
```

| Campo | Padrão | Descrição |
|---|---|---|
| `url` | (obrigatório) | Destino do POST. Só `https` (o payload leva localização) |
| `frequenciaSegundos` | 60 | Intervalo entre envios; mínimo 10, máximo 86400 |
| `token` | token da lista | `Authorization: Bearer`. Se ausente, usa o token configurado no app, desde que a URL seja do mesmo servidor da lista |
| `localizacao` | `true` | `false` não pede nem envia localização |

Conteúdo do `payload` (resumido). Todo POST tem o formato `{ "tipo": "telemetria", "payload": { ... } }`; a **referência completa**, com o envelope, as variações e comandos de teste, está em "Payloads: o que o app envia e recebe". O seu servidor recebe assim:

```json
{ "timestamp": "2026-11-20T21:30:00Z",
  "dispositivo": { "nome": "TABLET-07", "id": "9f3a...", "modelo": "Galaxy Tab A8", "android": "11", "app": "1.0.3" },
  "rede": { "tipo": "wifi", "ip": "192.168.0.15", "sinalDbm": -58, "sinalNivel": 3, "velocidadeMbps": 144 },
  "bateria": { "nivel": 87, "carregando": true, "fonte": "usb", "temperaturaC": 31.5 },
  "localizacao": { "status": "ok", "lat": -23.55, "lon": -46.63, "precisaoM": 12.0, "idadeSegundos": 4, "provedor": "gps" },
  "usuarios": [ { "sistema": "Webmail", "login": "joao", "desde": "2026-11-20T21:00:00Z", "fonte": "cookie", "modo": "continuo" } ],
  "usuarioAtual": { "sistema": "Webmail", "login": "joao" } }
```

- **Localização:** na primeira vez o Android pede a permissão ao usuário (se negar, vai `{"status":"sem_permissao"}`). Usa GPS e rede; sem sinal ainda, `{"status":"sem_fix"}`. Com `localizacao: false` o campo vai `null`. `idadeSegundos` diz quão antigo é o último fix.
- **Sinal:** no Wi-Fi vem `sinalDbm` (RSSI) e `sinalNivel` (0 a 4). Em rede celular o valor depende do aparelho e pode não vir.
- **Falhas:** sem rede ou resposta de erro, o envio é descartado (não há fila). O resultado do último envio aparece em Configurações.
- **Privacidade:** o envio de localização de pessoas é dado pessoal (LGPD); avise os usuários e use apenas em aparelhos da empresa.

## Identificação do usuário (quem está logado em cada sistema)
Cada item de `apps` pode ter um bloco `usuario` que diz como descobrir **quem está logado naquele sistema**. Vários sistemas, cada um com o seu usuário e o seu tipo de detecção. Nada é detectado sem esse bloco. **A senha nunca é lida nem enviada**: só o login.

```json
"usuario": { "tipo": "endpoint", "url": "/api/me", "campo": "login", "intervaloSegundos": 30 }
```

Campos de `usuario` (os que valem dependem do `tipo`):

| `tipo` | Como detecta | Campos |
|---|---|---|
| `endpoint` | Chama um endereço do sistema com a sessão do usuário e extrai o login do JSON. Sem sessão, vem 401/vazio: só sucesso gera usuário | `url` (relativa ou absoluta), `metodo` (GET), `campo`, `claim` |
| `elemento` | Lê o texto (ou `atributo`) de um elemento da tela | `seletor` (CSS), `atributo` |
| `cookie` | Lê um cookie (também HttpOnly), ou um claim se for JWT | `nome`, `campo`, `claim` |
| `storage` | Lê uma chave do `localStorage` (ou `sessionStorage`) | `nome`, `armazenamento` (`local` ou `sessao`), `campo`, `claim` |
| `js` | Avalia uma expressão na página | `expressao` (ex.: `window.APP.user.login`) |
| `url` | Extrai o login do endereço da página | `regex` (grupo 1) |
| `titulo` | Extrai o login do título da página | `regex` (grupo 1) |
| `resposta` | Observa uma chamada que a página já faz e extrai o login da resposta (ou de um cabeçalho) | `url` (glob), `metodo`, `campo`, `claim`, `cabecalho` |
| `formulario` | Pega o campo de usuário no envio do login; só vale se a regra de sucesso confirmar | `seletor` (opcional, senão detecta sozinho), `sucesso` |
| `requisicao` | Intercepta a chamada de login e extrai só o campo de usuário do corpo enviado | `url` (glob), `metodo` (POST), `campo`, `sucesso` |
| `cracha` | O app pede ao usuário que leia o crachá (câmera) ou digite a matrícula, ao abrir o sistema | `regex` (formato aceito), `crachaObrigatorio` (true), `limparNoTimeout` (true) |

Campos comuns: `regex` (aplicado ao valor lido; usa o grupo 1 se houver), `campo` (caminho `a.b[0].c` num JSON), `claim` (informação dentro de um JWT; o token nunca sai da página), `intervaloSegundos` (releitura periódica dos tipos de consulta; padrão 15, `0` desliga), `envio` (`"evento"` ou `"telemetria"`; muda o envio padrão do tipo, veja abaixo).

**Várias detecções no mesmo sistema:** `usuario` aceita um objeto ou uma **lista** de objetos. Cada detecção funciona sozinha e tem o seu usuário, útil para cruzar fontes ou ter um plano B:
```json
"usuario": [
  { "tipo": "formulario", "seletor": "#nomeusuario", "sucesso": { "tipo": "url", "regex": "/ConvocacaoAtiva" } },
  { "tipo": "cookie", "nome": "token", "claim": "email" },
  { "tipo": "cracha", "crachaObrigatorio": false }
]
```

**Para onde cada detecção envia** (padrão por tipo; `envio` troca):

| Envio | Tipos (padrão) | Destino |
|---|---|---|
| `evento` | `formulario`, `requisicao`, `resposta`, `cracha` | Na hora, a cada login/logout/troca, com `tipo: "evento"` (endpoint próprio ou o da telemetria) |
| `telemetria` | `endpoint`, `elemento`, `cookie`, `storage`, `js`, `url`, `titulo` | Só a telemetria periódica (campo `usuarios`) |

Por padrão os eventos vão para o **mesmo endpoint da telemetria** (o campo `tipo` do envelope diferencia, veja "Payloads"). Para mandar para um endereço próprio, ou desligar, use o bloco `eventos` na raiz do JSON:
```json
"eventos": { "url": "https://api.exemplo.com/hubweb/eventos", "token": "opcional", "ativo": true }
```
Sem `url`, vale o endpoint da telemetria. `"ativo": false` desliga os eventos. Precisa ser `https`. O token é opcional; sem ele vale o da telemetria (se o destino for o mesmo) ou o da lista (se for o mesmo servidor).

**Regra de sucesso** (`formulario` e `requisicao`), objeto `sucesso`:
- `tipo`: `senhaSumiu` (padrão; só olha se o campo de senha sumiu da tela, sem ler o valor), `url` (`regex` da URL depois do login), `elemento` (`seletor` que aparece) ou `endpoint` (`url`, `campo`).
- `espera`: segundos aguardando o sucesso antes de descartar a tentativa (padrão 10). Vale também se o login recarregar a página.
- Em `requisicao`: `status` (lista de códigos aceitos; padrão 2xx) e `campo` (caminho que precisa existir na resposta).

### Exemplo de cada tipo

**`endpoint`**: chama o endereço do sistema e pega o login do JSON. Com `claim`, o valor lido (`campo`) é um JWT e o login vem de dentro dele:
```json
"usuario": { "tipo": "endpoint", "url": "/api/me", "metodo": "GET", "campo": "login", "intervaloSegundos": 30 }
"usuario": { "tipo": "endpoint", "url": "/api/auth/token", "campo": "token", "claim": "preferred_username" }
```

**`elemento`**: texto do elemento, ou um atributo dele:
```json
"usuario": { "tipo": "elemento", "seletor": "#nome-usuario" }
"usuario": { "tipo": "elemento", "seletor": "#nome-usuario", "atributo": "data-login" }
```

**`cookie`**: valor do cookie, ou um claim se ele for um JWT:
```json
"usuario": { "tipo": "cookie", "nome": "usuario" }
"usuario": { "tipo": "cookie", "nome": "token", "claim": "email" }
```

**`storage`**: chave do `localStorage` (ou `sessionStorage`); `campo` entra no JSON guardado, `claim` abre um JWT:
```json
"usuario": { "tipo": "storage", "armazenamento": "local", "nome": "auth", "campo": "user.login" }
"usuario": { "tipo": "storage", "armazenamento": "sessao", "nome": "jwt", "claim": "sub" }
```

**`js`**: expressão avaliada na página (precisa devolver o login):
```json
"usuario": { "tipo": "js", "expressao": "window.APP && window.APP.user && window.APP.user.login" }
```

**`url`** e **`titulo`**: o login sai de uma expressão regular (grupo 1):
```json
"usuario": { "tipo": "url", "regex": "/u/([^/]+)/" }
"usuario": { "tipo": "titulo", "regex": "^Painel de (.+)$" }
```

**`resposta`**: observa uma chamada que a página já faz (`url` é um glob de `host/caminho`):
```json
"usuario": { "tipo": "resposta", "url": "app.exemplo.com/api/me", "campo": "usuario.login" }
"usuario": { "tipo": "resposta", "url": "app.exemplo.com/api/*", "cabecalho": "X-Usuario" }
```

**`formulario`**: detecta o campo de usuário sozinho, ou use `seletor`. A tentativa só vale se a regra de `sucesso` confirmar:
```json
"usuario": { "tipo": "formulario" }
"usuario": { "tipo": "formulario", "seletor": "#login", "sucesso": { "tipo": "url", "regex": "/home", "espera": 10 } }
"usuario": { "tipo": "formulario", "sucesso": { "tipo": "elemento", "seletor": "#menu-principal" } }
"usuario": { "tipo": "formulario", "sucesso": { "tipo": "endpoint", "url": "/api/me", "campo": "login" } }
```

**`requisicao`**: lê só o campo de usuário do corpo da chamada de login (JSON, formulário ou `FormData`):
```json
"usuario": {
  "tipo": "requisicao", "url": "app.exemplo.com/api/login", "metodo": "POST", "campo": "username",
  "sucesso": { "status": [200], "campo": "token" }
}
```

**`cracha`**: o app pergunta ao abrir o sistema; `regex` valida a matrícula:
```json
"usuario": { "tipo": "cracha", "regex": "^\\d{6}$", "crachaObrigatorio": true, "limparNoTimeout": true }
```

O arquivo `exemplo-apps.json` (e a tela **Exemplo de JSON** do app) traz um item para cada um desses casos.

**Regra de logout** (opcional; útil em `formulario` e `requisicao`, que não detectam a saída sozinhos), objeto `logout`:
- `tipo`: `url` (padrão; `regex` aplicada ao endereço da página) ou `elemento` (`seletor` que existe na tela de saída).
- Quando a página casa, ela é a tela de login ou de saída: ninguém está logado, e o app registra o `logout`. Num webmail, voltar para a tela de login (pelo "Sair" ou por sessão expirada) é o caso típico:
```json
"usuario": { "tipo": "formulario", "seletor": "#nomeusuario",
  "sucesso": { "tipo": "url", "regex": "/mail/(inbox|home)" },
  "logout":  { "tipo": "url", "regex": "/mail/login\\.html" } }
```

**O mesmo usuário entrando de novo:** cada login **confirmado** por uma ação (`formulario`, `requisicao` ou `cracha`) gera um evento `login`, mesmo que seja o mesmo usuário de antes, e renova o `desde`. Sem a regra de `logout`, quem sai e entra de novo gera dois `login` seguidos, sem `logout` no meio; com ela, o servidor recebe `logout` e depois `login`. Os tipos de consulta contínua (`cookie`, `endpoint`...) não repetem o evento enquanto o login for o mesmo, porque são lidos de tempos em tempos e repetir geraria um evento a cada leitura. A `resposta` também não repete por padrão (ela observa qualquer chamada que casar com `url`); se essa chamada **for a de login**, acrescente `"repetir": true` e cada resposta passa a contar como um novo login:
```json
"usuario": { "tipo": "resposta", "url": "app.exemplo.com/api/login", "metodo": "POST", "campo": "usuario.login", "repetir": true }
```

**Como o app trata o resultado**
- Cada detecção guarda o seu próprio usuário (um sistema pode ter várias). Mudou o login → `troca`; o primeiro → `login`; sumiu → `logout` (nos tipos de consulta, só depois de duas leituras vazias seguidas).
- **Detecção por evento** (`formulario`, `requisicao`, `resposta`, `cracha`): cada login/logout/troca vai **na hora**, com `tipo: "evento"`, para o **mesmo endpoint da telemetria** ou, se houver `eventos.url`, para um próprio. `"eventos": { "ativo": false }` desliga. Veja "Payloads".
- **Detecção contínua** (`endpoint`, `elemento`, `cookie`, `storage`, `js`, `url`, `titulo`): não gera evento; o usuário aparece na telemetria periódica.
- Na telemetria, `usuarios` lista o que **cada detecção** encontrou (com `sistema`, `login`, `fonte` e `modo`: `evento` ou `continuo`) e `usuarioAtual` é o mais recente. Veja "Payloads".
- Os usuários valem até o aparelho reiniciar (como os cookies). Em `cracha`, também são desligados quando o timeout de inatividade volta ao launcher (`limparNoTimeout`).
- Em Configurações aparece a lista "Usuários identificados", para depurar.
- Em `cracha`, a barra do sistema ganha um botão de pessoa para trocar de usuário.

**Limites:** a detecção só roda no documento principal da página (não em iframes), em endereços permitidos pelo `urlPermitida`. O tipo `js` executa código vindo do JSON e não funciona em páginas com política de segurança (CSP) que bloqueia `eval`. A detecção em JavaScript é instalada quando a página começa a carregar, mas pode perder chamadas feitas antes disso. Identificar pessoas é dado pessoal (LGPD): avise os usuários.

## Payloads: o que o app envia e recebe

### Todas as chamadas de rede do app

| Chamada | Método | Quando | Autenticação |
|---|---|---|---|
| Lista (JSON de configuração) | `GET` na URL das Configurações | Conforme `atualizacao.lista` (padrão: ao abrir o app, no mínimo a cada 60 s) e no botão de atualizar, que ignora o intervalo | `Authorization: Bearer <token>` se houver token **e** a URL for `https` |
| Imagem de fundo (`fundoUrl`) | `GET` | Só quando o `fundoUrl` muda | Token só se for o mesmo servidor da lista e `https` |
| Logo do cabeçalho (`aparencia.logoUrl`) | `GET` | Só quando o `logoUrl` muda | Igual à imagem de fundo |
| Dicas (`dicas.url`) | `GET` | Conforme `atualizacao.dicas` (padrão: ao abrir o app, no mínimo a cada 60 s) e na hora em que a `url` muda | `dicas.token`; senão o da lista, se for o mesmo servidor; só envia o token por `https` |
| Telemetria (`telemetria.url`) | `POST` JSON, `tipo: "telemetria"` | A cada `frequenciaSegundos`, com o app aberto | `telemetria.token`; senão o da lista, se for o mesmo servidor; só `https` |
| Eventos de usuário | `POST` JSON, `tipo: "evento"` | Na hora de cada login/logout/troca das detecções por evento | Para `eventos.url` (endpoint próprio) ou, sem ele, para a `telemetria.url`; token do destino, senão o da lista (mesmo servidor); só `https` |

Tempo limite de 10 s para conectar e de 15 s para ler (20 s na imagem de fundo). Telemetria e eventos **não têm fila nem nova tentativa**: se um envio falhar, ele se perde (a lista e a imagem são baixadas de novo na próxima atualização). O servidor só precisa responder com um status `2xx`; o corpo da resposta é ignorado.

**O que o app nunca envia:** senhas (nem a do administrador), tokens ou cookies, o conteúdo das páginas, nem o que o usuário digita. Dos logins, só o campo configurado.

### O envelope: `tipo` e `payload`

**Todo POST do app tem exatamente duas chaves**, `tipo` e `payload`. O `tipo` diz o que é, e o `payload` traz o conteúdo:

```json
{ "tipo": "telemetria", "payload": { "...": "..." } }
{ "tipo": "evento",     "payload": { "...": "..." } }
```

| `tipo` | Quando | O que vem em `payload` |
|---|---|---|
| `telemetria` | A cada `frequenciaSegundos` | Aparelho, rede, bateria, localização e usuários (seção 2) |
| `evento` | Login, troca ou logout de usuário | O evento, o sistema, o usuário e o aparelho (seção 3) |

Por isso **um endpoint só atende tudo**: o servidor lê o `tipo` e decide o que fazer. Tipos novos entram no mesmo formato em versões futuras; um receptor deve responder `2xx` e ignorar os que não conhece.

### Um endpoint ou dois

- **Um só (padrão):** configure só `telemetria.url`. Os eventos vão para o mesmo endereço, com `tipo: "evento"`. Pode ficar sem o bloco `eventos`, ou ter `"eventos": {}`.
- **Dois:** `"eventos": { "url": "https://.../eventos", "token": "opcional" }` manda os eventos para um endereço próprio. O formato é o mesmo (`tipo` e `payload`).
- **Sem eventos:** `"eventos": { "ativo": false }`.

Token dos eventos: o de `eventos.token`; se não houver e o destino for o da telemetria, o de `telemetria.token`; senão o token da lista, desde que seja o mesmo servidor. Sem `telemetria.url` e sem `eventos.url` não há para onde enviar, e nada é enviado.

### 1. Lista (o app baixa)

```
GET https://api.exemplo.com/hubweb/apps
Accept: application/json
Authorization: Bearer <token>        (só se houver token e a URL for https)
```

Resposta: `200` com o JSON descrito em "Formato do JSON" (veja o `exemplo-apps.json`). O app guarda a última resposta válida; se a URL cair, ou o JSON vier inválido ou acima de 10 MB, ele mantém a anterior e mostra o aviso vermelho no launcher.

### 2. Telemetria: `tipo: "telemetria"`

```
POST https://api.exemplo.com/hubweb/telemetria
Content-Type: application/json; charset=utf-8
Authorization: Bearer <token>        (se houver)
```

Exemplo completo (Wi-Fi, GPS, usuário identificado por cookie e por formulário):
```json
{
  "tipo": "telemetria",
  "payload": {
    "timestamp": "2026-11-20T21:30:00Z",
    "dispositivo": { "nome": "TABLET-07", "id": "9f3a1c2b7d4e5f60", "modelo": "Galaxy Tab A8", "android": "11", "app": "1.0.2" },
    "rede": { "tipo": "wifi", "ip": "192.168.0.15", "sinalDbm": -58, "sinalNivel": 3, "velocidadeMbps": 144 },
    "bateria": { "nivel": 87, "carregando": true, "fonte": "usb", "temperaturaC": 31.5, "saude": "boa", "tensaoMv": 4120, "ciclos": 212 },
    "localizacao": { "status": "ok", "lat": -23.5505, "lon": -46.6333, "precisaoM": 12.0, "idadeSegundos": 4, "provedor": "gps" },
    "hardware": {
      "memoria": { "totalMb": 3700, "livreMb": 1180, "baixa": false },
      "armazenamento": { "totalMb": 25600, "livreMb": 9800 },
      "ligadoHaSegundos": 86400, "reinicios": 14, "modoAviao": false, "telaLigada": true
    },
    "seguranca": { "deviceOwner": false, "modoKiosk": true, "bloqueioDeTela": false, "patchSeguranca": "2025-06-01", "root": false },
    "app": {
      "tela": "browser", "sistemaAberto": "Webmail", "abertoHaSegundos": 5400, "ultimoToqueHaSegundos": 12,
      "naMemoria": [ { "sistema": "Webmail", "carregando": false, "carregado": true, "erro": null } ]
    },
    "lista": { "aplicadaEm": "2026-11-20T21:00:00Z", "erro": null, "versao": 1, "sistemas": 6 },
    "dicas": { "aplicadaEm": "2026-11-20T21:00:03Z", "erro": null },
    "uso": { "periodoSegundos": 60, "sistemas": [ { "sistema": "Webmail", "segundos": 48, "aberturas": 1 } ] },
    "usuarios": [
      { "sistema": "Webmail", "login": "joao.silva", "desde": "2026-11-20T21:00:00Z", "fonte": "cookie", "modo": "continuo" },
      { "sistema": "Webmail", "login": "joao.silva", "desde": "2026-11-20T21:00:05Z", "fonte": "formulario", "modo": "evento" }
    ],
    "usuarioAtual": { "sistema": "Webmail", "login": "joao.silva" }
  }
}
```

Campos do `payload` (os nomes abaixo são relativos a ele):

| Campo | Tipo | Descrição |
|---|---|---|
| `timestamp` | texto | Data/hora UTC (ISO 8601, com `Z`), de quando o pacote foi montado |
| `dispositivo.nome` | texto | Nome do aparelho no Android; se vazio, o do Bluetooth; por fim fabricante e modelo |
| `dispositivo.id` | texto | Android ID: identificador estável deste aparelho para o app (muda em reset de fábrica ou se a chave de assinatura do app mudar) |
| `dispositivo.modelo` / `.android` / `.app` | texto | Fabricante e modelo; versão do Android; versão do HubWeb |
| `rede.tipo` | texto | `wifi`, `celular`, `ethernet`, `outra` ou `nenhuma` |
| `rede.ip` | texto ou `null` | IPv4 local (prefere o Wi-Fi); `null` sem rede |
| `rede.sinalDbm` | número | Força do sinal em dBm (negativo; quanto mais perto de 0, melhor). No Wi-Fi sempre que o Android informar; em rede celular só em aparelhos e versões que informam (Android 10+); senão o campo não vem |
| `rede.sinalNivel` | 0 a 4 | Só no Wi-Fi: 0 sem sinal, 4 excelente |
| `rede.velocidadeMbps` | número | Só no Wi-Fi: velocidade do link negociada |
| `bateria.nivel` | 0 a 100 ou `null` | Percentual |
| `bateria.carregando` | verdadeiro/falso | Carregando ou cheia |
| `bateria.fonte` | texto ou `null` | `tomada`, `usb`, `sem fio`; `null` fora do carregador |
| `bateria.temperaturaC` | número | Graus Celsius, quando o aparelho informa |
| `bateria.saude` | texto | `boa`, `superaquecida`, `ruim`, `sobretensao`, `fria` ou `falha`; ausente se o aparelho não informa |
| `bateria.tensaoMv` / `.ciclos` | número | Tensão em mV; ciclos de carga (só Android 14+ e só em aparelhos que informam) |
| `rede.ssid` | texto | Só no Wi-Fi e com a permissão de localização concedida: o nome da rede |
| `rede.servidor` | objeto | Conexão com o servidor da telemetria, medida no envio anterior (5 conexões TCP): `latenciaMs` (mediana, `null` se todas falharam), `perdaPct` (conexões que falharam, de 0 a 100), `tentativas`, `medidoEm`. Ausente no primeiro envio |
| `hardware.memoria` | objeto | `totalMb`, `livreMb` e `baixa` (o Android sinalizou pouca memória) |
| `hardware.armazenamento` | objeto | `totalMb` e `livreMb` do armazenamento interno |
| `hardware.ligadoHaSegundos` / `.reinicios` | número | Tempo desde que o aparelho ligou; quantas vezes já reiniciou (ausente se o Android não informa) |
| `hardware.modoAviao` / `.telaLigada` | verdadeiro/falso | Modo avião ligado; tela acesa |
| `seguranca.deviceOwner` / `.modoKiosk` | verdadeiro/falso | O HubWeb é device owner; o modo kiosk está ativo |
| `seguranca.bloqueioDeTela` | verdadeiro/falso | O aparelho tem senha, PIN ou padrão de bloqueio |
| `seguranca.patchSeguranca` | texto | Data do patch de segurança do Android (`AAAA-MM-DD`) |
| `seguranca.root` | verdadeiro/falso | Indício de root (heurística: build de teste ou binário `su`); quem esconde o root não é detectado |
| `app.tela` | texto | `launcher`, `browser` (um sistema aberto) ou `settings` |
| `app.sistemaAberto` | texto ou `null` | `nome` do sistema na tela agora |
| `app.naMemoria[]` | lista | Sistemas mantidos abertos: `sistema`, `carregando`, `carregado` (a primeira carga terminou) e `erro` (último erro de carga, ex.: `net::ERR_NAME_NOT_RESOLVED`, ou `null`) |
| `app.abertoHaSegundos` / `.ultimoToqueHaSegundos` | número | Há quanto tempo o app está rodando; há quanto tempo não recebe toque nem tecla |
| `lista` | objeto | `aplicadaEm` (UTC da última lista aplicada, `null` se nunca), `erro` (da última busca, ou `null`), `versao` (a chave `versao` do JSON, se houver) e `sistemas` (quantidade) |
| `dicas` | objeto | `aplicadaEm` e `erro`, como na lista |
| `uso` | objeto | Uso desde o último envio bem-sucedido (se um envio falha, o uso vai junto no seguinte): `periodoSegundos` e `sistemas[]` com `sistema`, `segundos` (tempo na tela, com o app em primeiro plano) e `aberturas` |
| `localizacao` | objeto ou `null` | `null` se a lista desligou a localização (`telemetria.localizacao: false`) |
| `localizacao.status` | texto | `ok`, `sem_permissao` (o usuário negou) ou `sem_fix` (ainda sem posição). Só com `ok` vêm os campos abaixo |
| `localizacao.lat` / `.lon` | número | Graus decimais |
| `localizacao.precisaoM` | número | Raio de incerteza em metros |
| `localizacao.idadeSegundos` | número | Há quanto tempo foi obtida essa posição |
| `localizacao.provedor` | texto | `gps` ou `network` |
| `usuarios` | lista | O que **cada detecção** de usuário encontrou, em todos os sistemas; vazia se nenhuma achou ninguém |
| `usuarios[].sistema` | texto | `nome` do item em `apps` |
| `usuarios[].login` | texto | O login detectado |
| `usuarios[].desde` | texto | Quando essa detecção viu esse login (UTC) |
| `usuarios[].fonte` | texto | Tipo da detecção: `endpoint`, `elemento`, `cookie`, `storage`, `js`, `url`, `titulo`, `resposta`, `formulario`, `requisicao` ou `cracha` |
| `usuarios[].modo` | texto | `evento` (essa detecção também envia eventos) ou `continuo` (só aparece aqui) |
| `usuarioAtual` | objeto ou `null` | O login detectado mais recentemente, em qualquer sistema |

Variações do `payload` (só os campos que mudam):

```json
{ "rede": { "tipo": "celular", "ip": "10.20.30.40", "sinalDbm": -92 },
  "localizacao": { "status": "sem_permissao" }, "usuarios": [], "usuarioAtual": null }
```
```json
{ "rede": { "tipo": "nenhuma", "ip": null },
  "bateria": { "nivel": 14, "carregando": false, "fonte": null },
  "localizacao": { "status": "sem_fix" } }
```
```json
{ "localizacao": null }
```
(o último é o caso de `"localizacao": false` no bloco `telemetria`)

Com o aparelho sem rede, o envio falha e se perde, então `rede.tipo: "nenhuma"` raramente chega ao servidor: só se a conexão voltar entre a leitura dos dados e o envio.

### 3. Eventos de usuário: `tipo: "evento"`

```
POST https://api.exemplo.com/hubweb/telemetria     (o mesmo da telemetria)
POST https://api.exemplo.com/hubweb/eventos        (se houver "eventos.url")
Content-Type: application/json; charset=utf-8
Authorization: Bearer <token>        (se houver)
```

Só as detecções **por evento** (`formulario`, `requisicao`, `resposta`, `cracha`, ou qualquer uma com `"envio": "evento"`) enviam, e só se os eventos não estiverem desligados (`eventos.ativo`). Os tipos de consulta contínua não geram evento: aparecem em `usuarios` na telemetria.

Login (a primeira vez que a detecção vê alguém):
```json
{
  "tipo": "evento",
  "payload": {
    "evento": "login",
    "timestamp": "2026-11-20T21:00:00Z",
    "dispositivo": { "nome": "TABLET-07", "id": "9f3a1c2b7d4e5f60", "modelo": "Galaxy Tab A8", "android": "11", "app": "1.0.2" },
    "sistema": "Webmail",
    "usuario": "joao.silva",
    "fonte": "formulario",
    "usuarioAnterior": null
  }
}
```

Troca (outro login na mesma detecção; `usuarioAnterior` traz quem estava):
```json
{ "tipo": "evento",
  "payload": { "evento": "troca", "timestamp": "2026-11-20T22:10:00Z", "dispositivo": { "...": "..." },
               "sistema": "Webmail", "usuario": "maria.souza", "fonte": "cracha", "usuarioAnterior": "joao.silva" } }
```

Logout (`usuario` é quem saiu):
```json
{ "tipo": "evento",
  "payload": { "evento": "logout", "timestamp": "2026-11-20T22:30:00Z", "dispositivo": { "...": "..." },
               "sistema": "Webmail", "usuario": "maria.souza", "fonte": "timeout", "usuarioAnterior": null } }
```

| Campo do `payload` | Descrição |
|---|---|
| `evento` | `login`, `troca` ou `logout` |
| `timestamp` | UTC, ISO 8601 |
| `dispositivo` | Igual ao da telemetria |
| `sistema` | `nome` do item em `apps`. Cada sistema tem o seu usuário; quem usa vários sistemas gera eventos separados para cada um |
| `usuario` | O login |
| `fonte` | Tipo da detecção que gerou o evento. No logout de um crachá desligado pelo timeout de inatividade, vale `timeout` |
| `usuarioAnterior` | Só na `troca`; senão `null` |

**Quando cada evento sai:** `login` na primeira vez que a detecção vê um login; `troca` quando o login muda; `logout` quando a detecção deixa de ver o usuário (nos tipos de consulta, depois de duas leituras vazias seguidas) ou quando o timeout desliga um crachá. O `formulario` não detecta logout por si só.

### 4. Testar o receptor com `curl`

```bash
# Telemetria
curl -X POST "https://api.exemplo.com/hubweb/telemetria" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"tipo":"telemetria","payload":{"timestamp":"2026-11-20T21:30:00Z","dispositivo":{"nome":"TESTE","id":"abc","modelo":"PC","android":"-","app":"1.0.2"},"rede":{"tipo":"wifi","ip":"192.168.0.15"},"bateria":{"nivel":80,"carregando":false,"fonte":null},"localizacao":null,"usuarios":[],"usuarioAtual":null}}'

# Evento de login (no mesmo endpoint; muda só o tipo)
curl -X POST "https://api.exemplo.com/hubweb/telemetria" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"tipo":"evento","payload":{"evento":"login","timestamp":"2026-11-20T21:00:00Z","dispositivo":{"nome":"TESTE","id":"abc","modelo":"PC","android":"-","app":"1.0.2"},"sistema":"Webmail","usuario":"joao.silva","fonte":"formulario","usuarioAnterior":null}}'
```
Uma resposta `2xx` (por exemplo `{"ok": true}`) é o que o app espera. O servidor deve atender os dois tipos e ignorar, com `2xx`, os que não conhece.
## Personalização do hub e da barra dos sistemas

### Cabeçalho do hub: bloco `aparencia` (raiz do JSON)
```json
"aparencia": {
  "nome": "Minha Empresa",
  "logoUrl": "https://exemplo.com/hubweb/logo.png",
  "logoAltura": 32,
  "ocultarAtualizar": false,
  "ocultarConfiguracoes": false,
  "ocultarUltimaAtualizacao": false,
  "toquesSecreto": 7,
  "colunas": 0
}
```

| Campo | Padrão | Efeito |
|---|---|---|
| `nome` | `HubWeb` | Título do cabeçalho do hub. Só muda o texto dentro do app; o nome embaixo do ícone no launcher do Android não muda |
| `logoUrl` | (sem logo) | Imagem (PNG/JPG/WebP, até 15 MB) mostrada no cabeçalho **no lugar do nome**, alinhada à esquerda. É baixada uma vez e guardada no aparelho; só baixa de novo se a URL mudar (igual ao `fundoUrl`). Sem o campo, a logo é removida e volta o nome. Se o download falhar, continua a anterior e tenta de novo na próxima atualização |
| `logoAltura` | `32` | Altura da logo em dp (16 a 96); a largura acompanha a proporção da imagem |
| `ocultarAtualizar` | `false` | Esconde o botão de recarregar a lista (e o indicador de carregamento do cabeçalho). Nas Configurações continua o botão **Atualizar agora**, que busca a lista e as dicas na hora, e as datas da última atualização de cada uma |
| `ocultarConfiguracoes` | `false` | Esconde a engrenagem. Veja o segredo abaixo |
| `ocultarUltimaAtualizacao` | `false` | Esconde a linha "Lista atualizada em ...". Os avisos de problema (sem acesso à lista, falha ao carregar) continuam aparecendo |
| `toquesSecreto` | `7` | Quantos toques rápidos (cada um a menos de 1,5 s do anterior; de 3 a 20) no título ou na logo abrem as configurações |
| `colunas` | `0` | Colunas do grid do hub (1 a 8). `0` ou ausente = automático, conforme a largura da tela |

**Segredo para entrar nas configurações:** com a engrenagem oculta, toque **7 vezes seguidas** no título (ou na logo) do cabeçalho. Funciona também com a engrenagem visível. Ele só abre o mesmo acesso da engrenagem: **se a lista tem senha de administração, ela continua sendo pedida**. O segredo esconde o caminho, não protege nada sozinho; a proteção é a senha.

Para o app mostrar a logo, a `logoUrl` precisa ser `http` ou `https`; o token da lista só vai na requisição se for o mesmo servidor e `https`.

### Barra dos sistemas: bloco `barra` (em cada item de `apps`)
```json
"barra": { "visivel": true, "voltar": true, "atualizar": true, "inicio": false }
```

| Campo | Padrão | Efeito |
|---|---|---|
| `visivel` | `true` | A barra já abre **visível** (`true`) ou **oculta** (`false`) quando o usuário entra no sistema. Oculta, aparece só o botãozinho no canto superior direito para mostrá-la; o usuário alterna quando quiser |
| `voltar` | `true` | Botão de voltar na página |
| `atualizar` | `true` | Botão de atualizar a página |
| `inicio` | `true` | Botão de página inicial |

O botão de volta ao launcher, o do leitor de código, o de trocar usuário (crachá) e o de esconder a barra não podem ser ocultados. Nos botões flutuantes (popup), `atualizar` e `inicio` também valem para o cabeçalho do popup (o de fechar fica sempre). O botão **Voltar do Android** continua navegando para trás normalmente.

## Atualização do próprio app (sem a Play Store)
O app procura versão nova sozinho no **GitHub Releases** do projeto (o repositório está fixo no app, em `AppUpdater.REPO`, em `AppUpdate.kt`; troque-o se fizer um fork). A lista **não** traz endereço, versão nem hash: ela só liga, desliga e regula a verificação, no bloco `atualizacao.app`:

```json
"atualizacao": { "app": { "ativo": true, "intervaloMinimoSegundos": 21600, "quando": ["abrir", "hub"], "espalharSegundos": 300 } }
```

- **`ativo`: `false` desliga a verificação por completo** (sem consulta ao GitHub, sem aviso e sem o botão nas Configurações). Padrão `true`.
- Os demais campos são os da regra da lista (veja "Atualização da lista e das dicas"). Padrão: a cada **6 h**, ao abrir o app e ao voltar ao hub. O intervalo fica guardado no aparelho.
- **Como acha a versão:** consulta o último Release (não rascunho, não pré-lançamento) e compara a tag (`v1.0.4`) com o `versionName` instalado. A consulta é anônima: o GitHub limita cerca de 60 por hora por endereço IP, então com muitos aparelhos na mesma rede mantenha o intervalo longo e use `espalharSegundos`.
- **O que baixa:** o primeiro anexo `.apk` do Release. O SHA-256 vem do próprio GitHub (campo `digest` do arquivo) ou, na falta dele, da linha "SHA-256 do APK" da descrição, que o workflow de release escreve.
- **Aparelho comum:** quando o hub aparece, há um aviso "Nova versão do HubWeb" com **Atualizar** e **Depois**. Na primeira vez o Android pede para liberar **"Instalar apps desconhecidos"** para o HubWeb; depois, o usuário **confirma a instalação** na tela do Android. Não há como instalar sem essa confirmação.
- **Device owner:** a instalação é **silenciosa**, feita sozinha quando o hub está na tela (para não derrubar um sistema em uso). O app fecha ao atualizar e tenta reabrir; o Android pode impedir isso em segundo plano.
- **Nas Configurações:** aparecem a versão instalada, a situação, o andamento e os botões **Verificar atualização do app** e **Atualizar o app agora**.
- **Segurança:** só https, só arquivos do próprio repositório (`github.com/<REPO>/releases/download/...`), SHA-256 conferido, pacote e `versionCode` conferidos, e o Android só aceita se a **assinatura for a mesma** do app instalado. Guarde bem a chave (`.jks`): quem a tem e consegue publicar um Release atualiza todos os aparelhos.
- **Para publicar uma atualização:** suba `versionCode` e `versionName` em `app/build.gradle.kts` e envie a tag `vX.Y.Z` (o workflow `release.yml` compila, assina e cria o Release). Não é preciso mexer na lista.
- **Play Store:** a política da loja não permite que um app instalado por ela se atualize por conta própria. Em versão publicada lá, retire o `AppUpdate.kt`, a permissão `REQUEST_INSTALL_PACKAGES` e os dois receivers do manifesto.

## Atualização da lista e das dicas (muitos aparelhos)

Quanto mais aparelhos, mais importa **quando** cada um consulta o servidor. Tudo isto é definido no próprio JSON da lista, no bloco `atualizacao`, com uma regra para a lista, outra para as dicas e outra para a verificação de versão nova do app (veja "Atualização do próprio app"):

```json
"atualizacao": {
  "lista": { "intervaloMinimoSegundos": 300, "quando": ["abrir", "hub"], "espalharSegundos": 30 },
  "dicas": { "intervaloMinimoSegundos": 900, "quando": ["abrir"],        "espalharSegundos": 60 },
  "app":   { "ativo": true }
}
```

| Campo | Padrão | Efeito |
|---|---|---|
| `intervaloMinimoSegundos` | `60` | Tempo mínimo entre duas buscas automáticas (de 10 a 86400). Conta desde a **última tentativa** (com sucesso ou falha) e fica guardado no aparelho: fechar e abrir o app **não** zera o intervalo |
| `quando` | `["abrir"]` | O que dispara uma busca automática: `abrir` (o app foi aberto ou trazido para a frente) e/ou `hub` (o usuário voltou ao launcher, por exemplo ao sair de um sistema ou das configurações). `[]` = nenhuma busca automática, só o botão |
| `espalharSegundos` | `0` | Cada busca automática espera um tempo **aleatório de 0 a N segundos** antes de sair. Serve para dezenas de aparelhos que abrem juntos (troca de turno) não baterem no servidor no mesmo instante |
| `ativo` | `true` | `false` **desliga a busca automática** daquela regra. Na lista e nas dicas o botão de atualizar continua funcionando; em `app`, a verificação de versão nova é desligada por completo |

**Como as regras se combinam**
- Uma busca automática só sai se o gatilho está em `quando` **e** o intervalo mínimo já passou. Os dois valem sempre; ausentes no JSON, valem os padrões acima (abrir, 60 s).
- **Exceções, que ignoram os gatilhos:** o botão de atualizar do hub, o **Atualizar agora** das configurações (lista e dicas) e o "Salvar" das configurações buscam a lista na hora, sem intervalo (só seguram um segundo toque em menos de 5 s e não esperam o `espalharSegundos`). Sem lista em cache (primeira vez) a busca sai em qualquer gatilho, mas ainda respeita o intervalo se a anterior falhou há pouco. Um endereço de dicas novo baixa já.
- **A regra vem do último JSON recebido** (fica em cache). Mudar `atualizacao` só vale depois que cada aparelho baixar a lista nova, o que pode levar até o intervalo antigo. Na primeira abertura, valem os padrões.
- A lista alterada manualmente pelo botão atualiza também as dicas só se o endereço delas mudou.
- Em Configurações aparece a regra em vigor: "Lista: ao abrir o app e ao voltar ao hub, no mínimo a cada 5 min, espalhada em até 30 s".

**Dimensionando (a conta)**
No pior caso, cada aparelho faz `3600 ÷ intervalo` buscas por hora de uso. Com 80 aparelhos: intervalo de 60 s ≈ 4 800 buscas por hora; 300 s ≈ 960; 900 s ≈ 320. A **telemetria** tem a sua própria frequência (`telemetria.frequenciaSegundos`, 60 s por padrão) e pesa mais: 80 aparelhos × 60 s ≈ 80 envios por minuto, **fora** deste bloco. Um ponto de partida razoável: lista a cada 5 a 15 min, dicas a cada 15 a 60 min, e `espalharSegundos` de 30 a 60.

## Dicas (telas de carregamento)

Como as dicas das telas de carregamento dos jogos: enquanto um sistema abre, o app mostra uma dica sorteada, em vez de uma tela em branco. É um **terceiro endpoint**, que o app só **baixa** (`GET`), com as configurações das dicas e a lista de dicas. O endereço fica no bloco `dicas` da lista:

```json
"dicas": { "url": "https://api.exemplo.com/hubweb/dicas", "token": "opcional" }
```

- O app baixa conforme a regra `atualizacao.dicas` (veja "Atualização da lista e das dicas") e na hora em que a `url` muda, e guarda o último JSON válido: se o endpoint cair, vale o cache. Se o bloco `dicas` sair da lista, as dicas somem e o cache é apagado.
- O token vai só por `https`; sem `dicas.token`, vale o da lista, se for o mesmo servidor.
- Sem o bloco `dicas`, ou sem nenhuma dica ativa, nada aparece.

### Formato do endpoint
Um objeto com `configuracoes` e `dicas` (a raiz também pode ser só a lista de dicas, com as configurações padrão). O arquivo `exemplo-dicas.json` e a aba **Exemplo de dicas** do app mostram um completo.

```json
{
  "versao": 1,
  "configuracoes": { "habilitado": true, "mostrarEm": ["abertura", "hub"], "ordem": "aleatoria",
                     "titulo": "Você sabia?", "atrasoMs": 400, "duracaoMinimaMs": 1500,
                     "trocarACadaSegundos": 8, "corFundo": "#1A237E", "corTexto": "#FFFFFF",
  "dicas": [
    { "id": "bateria", "titulo": "Bateria", "texto": "Coloque o tablet no carregador ao fim do turno.", "peso": 3 },
    { "id": "webmail-anexos", "texto": "Anexos acima de 20 MB não são enviados; use um link compartilhado.", "sistemas": ["Webmail"] }
  ]
}
```

**`configuracoes`**

| Campo | Padrão | Efeito |
|---|---|---|
| `habilitado` | `true` | `false` desliga as dicas sem tirar o bloco da lista |
| `mostrarEm` | `["abertura"]` | Onde aparecem (pode combinar): `abertura` = enquanto um sistema abre (a primeira página); `navegacao` = em toda página que demorar a carregar dentro do sistema; `hub` = uma caixa destacada, de cantos arredondados, com uma dica no launcher, logo acima do rodapé |
| `ordem` | `aleatoria` | `aleatoria` (respeita o `peso`) ou `sequencial` (em ordem, em volta) |
| `titulo` | `Dica` | Título padrão da dica (cada dica pode ter o seu) |
| `atrasoMs` | `400` | A tela de dica só aparece se o carregamento passar desse tempo (0 a 5000); páginas rápidas não piscam |
| `duracaoMinimaMs` | `1200` | Depois de aparecer, fica pelo menos esse tempo (0 a 10000), para dar tempo de ler |
| `trocarACadaSegundos` | `8` | Troca a dica a cada N s enquanto o carregamento durar, e na caixa do hub (3 a 120; `0` = não troca). O usuário também pode deslizar o dedo para os lados para ver a dica anterior ou a próxima, sem limite nos dois sentidos; ao deslizar, a contagem recomeça |
| `corFundo` / `corTexto` | índigo / branco | Cores da tela de dica durante o carregamento, em hexadecimal (`#RRGGBB`) |
| `hubCorFundo` / `hubCorTexto` | os de `corFundo` / `corTexto` | Cores da **caixa de dica do hub**. Se não forem informadas, a caixa usa `corFundo` e `corTexto` (e, sem elas, índigo e branco) |

**Cada item de `dicas`**

| Campo | Padrão | Efeito |
|---|---|---|
| `texto` | (obrigatório) | O texto da dica |
| `titulo` | o de `configuracoes` | Título desta dica |
| `id` | `d0`, `d1`... | Identificador livre (para você se organizar) |
| `sistemas` | todos | Lista de nomes de sistemas (o `nome` do item em `apps`) em que a dica vale. Vazio = qualquer sistema e o hub; na caixa do hub só aparecem as sem restrição |
| `peso` | `1` | Chance relativa no sorteio (1 a 100) |
| `ativa` | `true` | `false` esconde a dica sem apagá-la |

**Como a tela de dica se comporta:** cobre o sistema enquanto a página carrega, com o texto "Abrindo <sistema>", o título e a dica; **tocar nela a dispensa** e mostra a página. Se der erro de carregamento, o aviso de erro aparece por cima. Vale também para os sistemas abertos como botão flutuante (popup). Em Configurações aparece um resumo ("Dicas: N · atualizadas em ...") para conferir se o endpoint está sendo lido.

## Rodapé do launcher
Texto fixo na parte inferior do launcher, definido pelo campo `rodape` (raiz do JSON) e atualizado a cada 10 s. Variáveis aceitas:

| Variável | Valor |
|---|---|
| `{nome}` | Nome do aparelho (Configurações → Sobre o telefone → Nome do dispositivo; se vazio, nome do Bluetooth; por fim fabricante e modelo) |
| `{ip}` | IPv4 local (prefere o Wi-Fi); "sem rede" se não houver |
| `{modelo}` | Fabricante e modelo |
| `{android}` | Versão do Android |
| `{app}` | Versão do HubWeb |
| `{id}` | Android ID do aparelho |
| `{atualizacao}` | Data/hora da última lista recebida |

Exemplos: `"rodape": "{nome} · {ip}"`, `"rodape": "Expedição | {nome} | {ip} | v{app}"`, texto sem variáveis (`"Setor Expedição"`) ou várias linhas com `\n`. **Sem o campo `rodape` (ou com `""`), nada é mostrado.** Variáveis desconhecidas ficam como escritas.

## Botões flutuantes
Botões redondos e arrastáveis (lembram a posição) sobre o launcher e os sistemas. O ícone no topo do launcher abre "Botões flutuantes", onde o usuário liga/desliga cada um, sem senha. Vêm desligados por padrão.

- **Calculadora:** abre uma calculadora em popup. Sem nada na lista, ela está disponível, é opcional e vem desligada; o usuário a liga na lista de escolha, e "Ocultar botão" dentro do popup também a desliga. A lista controla isso com o bloco `calculadora` na raiz:

  ```json
  "calculadora": { "habilitada": true, "obrigatoria": false, "inicial": true }
  ```

  | Campo | Padrão | Efeito |
  |---|---|---|
  | `habilitada` | `true` | `false` tira a calculadora de vez: sem botão e sem a opção na lista de escolha |
  | `obrigatoria` | `false` | `true` deixa o botão sempre ligado; o usuário não desliga (a opção aparece travada e some o "Ocultar botão") |
  | `inicial` | não definido (desligada) | Só vale se for opcional: `true` já vem ligada na primeira vez que a lista chega, `false` desligada. Depois manda a escolha do usuário; mudar o JSON não religa nem desliga |
  | `travada` | `false` | `true` trava a posição do botão da calculadora: não dá para arrastar, e ele fica em `posicao` |
  | `posicao` | `"inferior-direita"` | Um canto ou `{ "x": 0.9, "y": 0.8 }`, como nos demais botões flutuantes. Só vale com `travada` |

  O antigo `"calculadoraInicial": true/false` na raiz continua valendo e equivale a só o `inicial`.
- **Itens de `apps`:** cada item decide onde aparece, com três parâmetros:

  | Parâmetro | Padrão | Efeito |
  |---|---|---|
  | `hub` | `true` | Aparece como cartão no launcher |
  | `flutuante` | `false` | Também (ou só) vira um botão flutuante com popup |
  | `flutuanteObrigatorio` | `false` | Só vale com `flutuante: true`. `true` = botão sempre ligado, o usuário não pode desligar (aparece travado na lista de escolha); `false` = o usuário escolhe |
  | `flutuanteInicial` | `false` | Só vale com `flutuante: true` e sem `flutuanteObrigatorio`. Define se o botão **já vem ligado** quando a lista é baixada pela primeira vez (ou quando o item aparece pela primeira vez). Depois disso vale só a escolha do usuário: mudar o JSON não religa nem desliga o que ele escolheu |
  | `flutuanteTravado` | `false` | Só vale com `flutuante: true`. `true` trava a posição do botão: não dá para arrastar, e ele fica em `flutuantePosicao` (a posição que o usuário tinha salvado é ignorada) |
  | `flutuantePosicao` | `"inferior-direita"` | Um canto (`"inferior-direita"`, `"inferior-esquerda"`, `"superior-direita"`, `"superior-esquerda"`, `"meio-direita"`, `"meio-esquerda"`) ou `{ "x": 0.9, "y": 0.8 }` (fração da área livre, 0 a 1). Vários botões travados sem `flutuantePosicao` se empilham no canto inferior direito; com posição explícita não empilham (escolha lugares diferentes) |

  Combinações: só hub (padrão); hub + flutuante (nos dois lugares); só flutuante (`hub: false, flutuante: true`). O botão mostra o ícone do item (ou a inicial) e abre a URL num popup com atualizar, início e fechar; Voltar navega no histórico do popup e só fecha sem histórico. Todos os outros campos do item (`urlPermitida`, `icone`, `permitir*`...) valem igual no hub e no popup.
- **Formato antigo:** o array `flutuantes` na raiz (com `obrigatorio`) ainda é aceito e equivale a itens `hub: false, flutuante: true`, mas use `apps`.
- **Travar a posição:** por padrão todos os botões são arrastáveis e lembram onde foram deixados. Há dois jeitos de travar: **(1) pelo JSON**, por botão, com `flutuanteTravado` e `flutuantePosicao` (e `travada`/`posicao` na calculadora), que fixam o botão no lugar escolhido e ignoram a posição que o usuário tinha salvado; **(2) pelo usuário**, com a chave **"Travar a posição dos botões"** na lista de botões flutuantes (o ícone no topo do launcher), que congela todos onde estão. Botão travado continua clicável; só não arrasta. A posição (`flutuantePosicao` e `calculadora.posicao`) aceita o canto como texto (`"inferior-esquerda"`) ou como objeto (`{ "canto": "inferior-esquerda" }`), além de `{ "x": 0.9, "y": 0.8 }`.
- Os popups ficam vivos (mantêm a página) até o app fechar e não contam no limite de sistemas abertos.

## Cookies
Valem enquanto o aparelho não reiniciar e são compartilhados entre os sistemas. No primeiro start após um boot, cookies e storage são apagados.

## Leitores de código de barras
Leitores em modo teclado (keyboard wedge) funcionam direto nos campos da página. Leitores por intent (DataWedge etc.) devem ser configurados para saída por teclado.

## Leitor de código pela câmera
Com `"leitorCamera": true` no item, a barra do sistema (e o cabeçalho do popup, se for flutuante) ganha um botão de leitor. Fluxo: o usuário toca no campo da página, toca no botão, aponta a câmera; o app **digita o código no campo focado como um teclado** (eventos de tecla reais, para as páginas que escutam keydown) e envia `Enter` (padrão), `Tab` ou nada, conforme `leitorSufixo`.

- Lê códigos de barras 1D (EAN, Code 128, Code 39, ITF...) e 2D (QR, Data Matrix, PDF417...), offline, com a biblioteca ZXing. Aceita a câmera em retrato ou paisagem.
- Precisa da permissão de câmera (pedida na primeira abertura).
- Quebras de linha e outros caracteres de controle (comuns no fim de QR codes) são descartados antes de digitar.
- Se as teclas não alterarem o campo (campos numéricos ou com máscara costumam ignorá-las), o app grava o texto direto no campo e dispara os eventos `input` e `change`; nesse caso o valor do campo é substituído pelo código. O mesmo vale para caracteres que o teclado virtual não mapeia (acentos, símbolos raros).
- É independente de `permitirCamera` (que libera a câmera para a própria página).

## Modo kiosk
- **Sem device owner:** o HubWeb **não** fixa a tela, porque o Android exibiria o aviso de como desafixar (tela fixada pelo próprio usuário). O kiosk só esconde as barras do sistema e bloqueia o Voltar no launcher; o gesto de Início ainda sai do app.
- **Com device owner (kiosk de verdade):** o HubWeb trava o aparelho (sem Início/Recentes), sem aviso e sem saída pelo sistema. Em aparelho recém-resetado e sem contas Google:

```
adb shell dpm set-device-owner br.com.hubweb/.AdminReceiver
```
Depois ligue **Modo kiosk** nas configurações (ou `modoKiosk: true` na lista).

**Como sair do kiosk (também está em Configurações, que exige a senha):**
1. Na tela inicial, engrenagem → senha → desligue "Modo kiosk" → Salvar. Se a lista define `modoKiosk`, altere ou remova o campo na lista e atualize.
2. "Encerrar o HubWeb" fecha o app.
3. Para desinstalar um app que é device owner: Configurações → "Remover device owner". Sem isso, só restaurando o aparelho de fábrica.

## Logo
Um hub (anel central) ligado por seis conexões a seis nós, unidos por uma teia hexagonal, sobre um degradê índigo → azul → ciano. Um dos nós é âmbar (`#FFCA28`) e dá identidade ao desenho. Cores: `#1A237E`, `#1976D2`, `#26C6DA`, `#FFCA28` e branco.

- **No app:** ícone adaptativo (`res/drawable/ic_launcher_background.xml` e `ic_launcher_foreground.xml`), com versão monocromática para o ícone temático do Android 13+ e um ícone simples para o Android 7.x (`res/mipmap-mdpi`).
- **Fora do app:** `logo/hubweb-icone.svg` (só o ícone) e `logo/hubweb-logo-horizontal.svg` (ícone e nome). São vetores, então servem em qualquer tamanho; para uma imagem PNG (por exemplo 512×512 para uma loja de apps), exporte o SVG em um editor como o Inkscape ou o Figma.

## Build
Requisitos: JDK 17 e o Android SDK (compileSdk 35). O Android Studio já traz os dois; ao abrir a pasta do projeto nele, o `local.properties` com o caminho do SDK é criado sozinho. Pela linha de comando, crie `local.properties` com `sdk.dir=<caminho do Android SDK>`.

```
./gradlew assembleDebug        # Windows: gradlew.bat assembleDebug
```
APK em `app/build/outputs/apk/debug/HubWeb <versão> debug.apk`.

**Release assinado:** copie `keystore.properties.example` para `keystore.properties`, preencha com a sua chave (o exemplo mostra como criá-la com o `keytool`) e rode `./gradlew assembleRelease`. O APK sai em `app/build/outputs/apk/release/HubWeb <versão>.apk`. Sem o `keystore.properties` o release sai **sem assinatura** e não instala. **Nunca** envie o `.jks` nem o `keystore.properties` ao repositório (o `.gitignore` já os ignora) e guarde a chave: sem a mesma chave os aparelhos não aceitam atualizar o app.

**Versão:** suba `versionCode` e `versionName` em `app/build.gradle.kts` a cada APK que for distribuir.

**Release automático (GitHub Actions):** o workflow `.github/workflows/release.yml` compila, assina e publica o release quando você envia uma tag `vX.Y.Z` (ela precisa ser igual ao `versionName`). Cadastre antes, em *Settings → Secrets and variables → Actions*, os segredos `KEYSTORE_BASE64` (o `.jks` em base64), `KEYSTORE_PASSWORD`, `KEY_ALIAS` e `KEY_PASSWORD`. Depois: suba a versão, faça o commit e rode `git tag v1.0.4 && git push origin v1.0.4`. O release sai com o `HubWeb-<versão>.apk` e o SHA-256 na descrição, que o app usa para achar e conferir a atualização (a lista não precisa ser alterada). O workflow `build.yml` valida os JSON de exemplo e compila o debug a cada push e pull request.

Este README e os exemplos (`exemplo-apps.json`, `exemplo-dicas.json`) são copiados para dentro do app a cada build; edite sempre os da raiz do projeto.

## Licença
MIT, veja o arquivo `LICENSE`. O app usa a biblioteca ZXing (Apache 2.0) para ler códigos de barras.
