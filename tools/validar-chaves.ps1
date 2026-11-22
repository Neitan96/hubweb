# Valida os JSON de exemplo contra as chaves que o app realmente lê.
#
# O que faz:
#   1. confere que os exemplos são JSON válido;
#   2. para cada bloco (raiz, item de apps, usuario, usuario.sucesso, aparencia...) compara as chaves que o app lê
#      (lista abaixo, tirada de ConfigRepo.kt, Dicas.kt e res/raw/hubweb_user.js) com as que o exemplo traz:
#        - FALTA NO EXEMPLO: o app lê, o exemplo não mostra;
#        - SOBRA NO EXEMPLO: o exemplo traz, o app não lê (chave com erro de digitação ou esquecida);
#   3. confere que toda chave tem a sua "<chave>_doc".
#
# Uso (na pasta kiosk):  powershell -ExecutionPolicy Bypass -File tools\validar-chaves.ps1
# Ao mudar o que o app lê, atualize a lista $esperado abaixo.

$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)

# Chaves que o app lê, por bloco. "legado" = aceitas só por compatibilidade (não precisam estar no exemplo).
$esperado = @{
  'raiz'              = @('novaConfigUrl','novoToken','timeoutInatividadeMinutos','sistemasAbertos','apps','fundoUrl','rodape','senhaSha256','senha','modoKiosk','aparencia','dicas','atualizacao','telemetria','eventos','calculadora')
  'apps[]'            = @('nome','urlInicial','urlPermitida','icone','userAgent','permitirCopiarColar','permitirHttp','ordem','grupo','orientacao','zoom','permitirCamera','permitirUpload','permitirDownload','leitorCamera','leitorSufixo','barra','usuario','hub','flutuante','flutuanteObrigatorio','flutuanteInicial','flutuanteTravado','flutuantePosicao')
  'sistemasAbertos[]' = @('ramMinimaMb','quantidade')
  'barra'             = @('visivel','voltar','atualizar','inicio')
  'aparencia'         = @('nome','logoUrl','logoAltura','ocultarAtualizar','ocultarConfiguracoes','ocultarUltimaAtualizacao','toquesSecreto','colunas')
  'dicas'             = @('url','token')
  'atualizacao'       = @('lista','dicas','app')
  'atualizacao.lista' = @('intervaloMinimoSegundos','quando','espalharSegundos','ativo')
  'atualizacao.dicas' = @('intervaloMinimoSegundos','quando','espalharSegundos','ativo')
  'atualizacao.app'   = @('intervaloMinimoSegundos','quando','espalharSegundos','ativo')
  'telemetria'        = @('url','frequenciaSegundos','token','localizacao')
  'eventos'           = @('url','token','ativo')
  'calculadora'       = @('habilitada','obrigatoria','inicial','travada','posicao')
  'posicao'           = @('x','y')
  'usuario'           = @('tipo','envio','limparNoTimeout','crachaObrigatorio','regex','url','metodo','campo','claim','seletor','atributo','nome','armazenamento','expressao','cabecalho','intervaloSegundos','repetir','sucesso','logout')
  'usuario.sucesso'   = @('tipo','regex','seletor','url','campo','status','espera')
  'usuario.logout'    = @('tipo','regex','seletor')
  # exemplo-dicas.json
  'dicas.raiz'        = @('configuracoes','dicas')
  'dicas.config'      = @('habilitado','mostrarEm','ordem','titulo','atrasoMs','duracaoMinimaMs','trocarACadaSegundos','corFundo','corTexto','hubCorFundo','hubCorTexto')
  'dicas.item'        = @('id','titulo','texto','sistemas','peso','ativa')
}
# Chaves que o exemplo traz mas o app não lê DE PROPÓSITO.
$informativas = @{ 'raiz' = @('versao'); 'dicas.raiz' = @('versao') }

$presentes = @{}   # bloco -> HashSet de chaves vistas (sem _doc)
$semDoc    = New-Object System.Collections.Generic.List[string]

function Visita($o, [string]$bloco) {
  if ($null -eq $o -or $o -isnot [System.Management.Automation.PSCustomObject]) { return }
  if (-not $presentes.ContainsKey($bloco)) { $presentes[$bloco] = New-Object 'System.Collections.Generic.HashSet[string]' }
  $nomes = $o.PSObject.Properties.Name
  foreach ($n in $nomes) {
    if ($n -like '*_doc' -or $n -eq '_doc') { continue }
    [void]$presentes[$bloco].Add($n)
    $filho = $o.$n
    switch ("$bloco|$n") {
      'raiz|aparencia'    { Visita $filho 'aparencia' }
      'raiz|dicas'        { Visita $filho 'dicas' }
      'raiz|atualizacao'  { Visita $filho 'atualizacao'; Visita $filho.lista 'atualizacao.lista'; Visita $filho.dicas 'atualizacao.dicas'; Visita $filho.app 'atualizacao.app' }
      'raiz|telemetria'   { Visita $filho 'telemetria' }
      'raiz|eventos'      { Visita $filho 'eventos' }
      'raiz|calculadora'  { Visita $filho 'calculadora' }
      'raiz|sistemasAbertos' { foreach ($i in @($filho)) { Visita $i 'sistemasAbertos[]' } }
      'raiz|apps'         { foreach ($i in @($filho)) { Visita $i 'apps[]' } }
      'calculadora|posicao' { Visita $filho 'posicao' }
      'apps[]|barra'      { Visita $filho 'barra' }
      'apps[]|flutuantePosicao' { Visita $filho 'posicao' }
      'apps[]|usuario'    { foreach ($u in @($filho)) { Visita $u 'usuario'; if ($u.sucesso) { Visita $u.sucesso 'usuario.sucesso' }; if ($u.logout) { Visita $u.logout 'usuario.logout' } } }
      'dicas.raiz|configuracoes' { Visita $filho 'dicas.config' }
      'dicas.raiz|dicas'  { foreach ($i in @($filho)) { Visita $i 'dicas.item' } }
    }
    # toda chave deve ter a sua _doc em algum lugar do arquivo (a doc fica na primeira ocorrência)
  }
}

function Docs($o, $comDoc, $todas) {
  if ($o -is [System.Management.Automation.PSCustomObject]) {
    foreach ($n in $o.PSObject.Properties.Name) {
      if ($n -like '*_doc') { [void]$comDoc.Add($n.Substring(0, $n.Length - 4)) } elseif ($n -ne '_doc') { [void]$todas.Add($n) }
      Docs $o.$n $comDoc $todas
    }
  } elseif ($o -is [System.Array]) { foreach ($i in $o) { Docs $i $comDoc $todas } }
}

$falhas = 0
foreach ($arq in 'exemplo-apps.json', 'exemplo-dicas.json') {
  $raw = [System.IO.File]::ReadAllText((Join-Path (Get-Location) $arq))
  try { $obj = $raw | ConvertFrom-Json } catch { "[$arq] JSON INVÁLIDO: $($_.Exception.Message)"; $falhas++; continue }
  "[$arq] JSON válido ($($raw.Length) caracteres)"
  $presentes.Clear()
  Visita $obj $(if ($arq -eq 'exemplo-apps.json') { 'raiz' } else { 'dicas.raiz' })
  foreach ($bloco in ($presentes.Keys | Sort-Object)) {
    $lidas = @($esperado[$bloco]); $vistas = @($presentes[$bloco])
    $falta = @($lidas | Where-Object { $_ -notin $vistas })
    $sobra = @($vistas | Where-Object { $_ -notin $lidas -and $_ -notin @($informativas[$bloco]) })
    if ($falta) { "  [$bloco] FALTA NO EXEMPLO: " + ($falta -join ', '); $falhas += $falta.Count }
    if ($sobra) { "  [$bloco] SOBRA NO EXEMPLO (o app não lê): " + ($sobra -join ', '); $falhas += $sobra.Count }
    if (-not $falta -and -not $sobra) { "  [$bloco] ok ($($vistas.Count) chaves)" }
  }
  $comDoc = New-Object 'System.Collections.Generic.HashSet[string]'; $todas = New-Object 'System.Collections.Generic.HashSet[string]'
  Docs $obj $comDoc $todas
  $sem = @($todas | Where-Object { -not $comDoc.Contains($_) } | Sort-Object)
  if ($sem) { "  SEM _doc em lugar nenhum: " + ($sem -join ', '); $falhas += $sem.Count } else { "  todas as $($todas.Count) chaves têm _doc" }
}
""
if ($falhas -eq 0) { "RESULTADO: tudo certo." } else { "RESULTADO: $falhas problema(s) acima."; exit 1 }
