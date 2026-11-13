# Dopa Cleaner

App Android (Kotlin + Compose) que varre armazenamento local e nuvem em busca de conteúdo adulto, com classificação **100% on-device** (TFLite) e tela de seleção/exclusão.

## Fontes

| Fonte | Como acessa | Varre | Exclui |
|---|---|---|---|
| Aparelho (interna, SD, USB OTG) | `MANAGE_EXTERNAL_STORAGE` + todos os `StorageVolume` montados | Tudo exceto `Android/data`, `Android/obb` | Sim (permanente) |
| Google Drive | OAuth `drive` (Identity AuthorizationClient) + REST v3 | Imagens/vídeos que você possui (via thumbnail) | Sim → lixeira do Drive |
| Google Fotos | Photos **Picker** API | Apenas itens que você seleciona no picker | **Não** (API não permite) |
| WebDAV (config) | PROPFIND recursivo, senha cifrada no Android Keystore | Tudo sob a URL | Sim |
| Pasta SAF (config) | `ACTION_OPEN_DOCUMENT_TREE` | Qualquer provider que apareça no seletor do Android | Se o provider suportar |

Vídeos: 3 quadros (25/50/75%), vale o de maior risco.

## Velocidade e segundo plano

- Modos em Configurações: Rápido (miniatura EXIF embutida + 1 quadro/vídeo), Equilibrado (EXIF + 2 quadros), Preciso (decodifica tudo + 3 quadros).
- Pool de interpretadores TFLite (núcleos/2) e workers ≈ núcleos + pool.
- Varredura em serviço foreground com WakeLock parcial: continua com tela apagada ou em outro app. Em Configurações há o atalho para remover a otimização de bateria (necessário em Xiaomi/Samsung/Motorola agressivos).

## Visualizador

Toque numa miniatura: abre em tela cheia com zoom (pinça/duplo toque) e player de vídeo (Media3/ExoPlayer, streaming autenticado para Drive/WebDAV/Fotos). Deslize para o próximo; Ignorar/Selecionar/Excluir na barra inferior. Pressão longa ou o círculo seleciona na grade.

## Cache incremental e agendamento

- Cada item analisado fica em SQLite local com uma versão (local: tamanho+mtime; Drive: md5; WebDAV: ETag/Last-Modified; SAF: last_modified). Item sem alteração não é reclassificado; mudar o limite reaplica sobre os scores salvos.
- Resultados sobrevivem a reinício do app. "Ignorar" é lembrado até o arquivo mudar.
- Itens que sumiram da fonte são removidos do cache quando a fonte termina sem erros.
- Agendamento diário/semanal via WorkManager, só carregando e com bateria ok. Google Fotos fica de fora (seleção manual). Drive só entra se o token renovar sem interação.

## Limitações reais

- **Google Fotos**: desde 31/03/2025 a Library API só lê mídia criada pelo próprio app. Leitura da biblioteca inteira só via Picker (seleção manual, links valem 60 min). Exclusão por terceiros não existe na API.
- **TeraBox**: não há API pública oficial/estável para terceiros. Não implementado. Alternativas: app TeraBox expor pasta ao seletor (SAF) ou sincronizar via outro cliente.
- **Escopo `drive`** é restrito: app não verificado funciona apenas para usuários de teste (até 100) no modo "Testing" do consentimento OAuth. Publicação exige verificação CASA.
- **Play Store** restringe `MANAGE_EXTERNAL_STORAGE`. Distribuição pretendida: APK sideload.
- Modelo: [GantMan/nsfw_model](https://github.com/GantMan/nsfw_model) MobileNetV2 (MIT), ~92% val. Falsos positivos ocorrem (praia, academia, arte). Revise antes de excluir.

## Build

```bash
./gradlew assembleRelease   # baixa e verifica (SHA-256) o modelo em app/src/main/assets/models/
```

CI (`.github/workflows/android.yml`) gera o APK como artifact `dopa-cleaner-apk`.

### Assinatura fixa (obrigatória para OAuth Google)

Sem secrets, debug e release são assinados com `app/dopa-shared.keystore` (commitado de propósito), então o SHA-1 é o mesmo em toda build:

```
SHA-1: AA:C9:6B:D4:1A:26:64:AE:99:87:00:9F:2B:EF:40:97:28:BB:A3:15
```

O app também mostra pacote e SHA-1 em Configurações > Conta Google. Risco: quem tem acesso ao repositório pode assinar um APK que se passa por atualização deste app. Para chave privada, defina os secrets `DOPA_KEYSTORE_BASE64`, `DOPA_KEYSTORE_PASSWORD`, `DOPA_KEY_ALIAS`, `DOPA_KEY_PASSWORD` (e cadastre o SHA-1 dela no Google Cloud).

### Google Cloud

1. Projeto novo → habilitar **Google Drive API** e **Google Photos Picker API**.
2. Tela de consentimento OAuth: tipo External, modo Testing, adicionar seu e-mail como test user, escopos `.../auth/drive` e `.../auth/photospicker.mediaitems.readonly`.
3. Credenciais → OAuth client ID tipo **Android**: pacote `com.dopa.cleaner` + SHA-1 do keystore acima.

Sem isso o app funciona só com aparelho/WebDAV/pastas.

## Privacidade

- Nenhuma imagem sai do aparelho para classificação.
- `FLAG_SECURE` bloqueia screenshots/recentes; prévias borradas por padrão (API 31+), ocultas no Android 11.
- Backup do app desativado; prévias ficam só em cache e são apagadas a cada varredura.
