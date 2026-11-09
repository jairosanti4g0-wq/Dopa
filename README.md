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

O SHA-1 do certificado precisa ser estável. Gere uma vez:

```bash
keytool -genkeypair -v -keystore dopa.jks -alias dopa -keyalg RSA -keysize 2048 -validity 10000
keytool -list -v -keystore dopa.jks -alias dopa | grep SHA1
```

Local: `keystore.properties` na raiz (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`).
CI: secrets `DOPA_KEYSTORE_BASE64` (`base64 -w0 dopa.jks`), `DOPA_KEYSTORE_PASSWORD`, `DOPA_KEY_ALIAS`, `DOPA_KEY_PASSWORD`.

### Google Cloud

1. Projeto novo → habilitar **Google Drive API** e **Google Photos Picker API**.
2. Tela de consentimento OAuth: tipo External, modo Testing, adicionar seu e-mail como test user, escopos `.../auth/drive` e `.../auth/photospicker.mediaitems.readonly`.
3. Credenciais → OAuth client ID tipo **Android**: pacote `com.dopa.cleaner` + SHA-1 do keystore acima.

Sem isso o app funciona só com aparelho/WebDAV/pastas.

## Privacidade

- Nenhuma imagem sai do aparelho para classificação.
- `FLAG_SECURE` bloqueia screenshots/recentes; prévias borradas por padrão (API 31+), ocultas no Android 11.
- Backup do app desativado; prévias ficam só em cache e são apagadas a cada varredura.
