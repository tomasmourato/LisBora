# Publicar na Google Play

O que já está feito no projeto e o que falta fazer na Play Console. As regras da Google Play mudam: confirma cada ponto na Console antes de enviar.

## No projeto (feito)

- `targetSdk` 37, bibliotecas nativas alinhadas a 16 KB (verificado no APK de release), R8 e redução de recursos no release.
- Perfil de arranque em `app/src/main/baseline-prof.txt` (gravado com `:baselineprofile`).
- Política de privacidade em [docs/PRIVACIDADE.md](PRIVACIDADE.md), também em "Sobre a LisBora" na app. Endereço público: `https://github.com/tomasmourato/LisBora/blob/main/docs/PRIVACIDADE.md`.
- Licenças de código aberto e atribuição dos dados no ecrã "Sobre a LisBora".
- Cópia de segurança do Android sem os pacotes de dados (passariam o limite de 25 MB).

## Chave de upload e App Bundle

1. Cria a chave de upload fora do repositório e guarda-a com uma cópia de segurança:

   ```bash
   keytool -genkeypair -v -keystore ~/lisbora-upload.jks -alias lisbora -keyalg RSA -keysize 4096 -validity 10000
   ```

2. Em `~/.gradle/gradle.properties` (nunca no repositório):

   ```properties
   lisbora.signing.storeFile=C:/Users/<utilizador>/lisbora-upload.jks
   lisbora.signing.storePassword=...
   lisbora.signing.keyAlias=lisbora
   lisbora.signing.keyPassword=...
   ```

   Sem estas propriedades, o release é assinado com a chave de debug: serve para testar, mas a Play recusa-o.

3. Sobe o `versionCode` (e o `versionName`) em `app/build.gradle.kts` a cada envio e gera o bundle:

   ```bash
   ./gradlew :app:bundleRelease
   ```

   O ficheiro fica em `app/build/outputs/bundle/release/app-release.aab`. Usa a assinatura de apps da Play: a Google guarda a chave de assinatura da app e a chave acima só serve para os envios.

## Na Play Console

- **Testes antes da produção**: as contas pessoais criadas depois de novembro de 2023 têm de fazer um teste fechado com pelo menos 12 testadores durante 14 dias antes de publicar.
- **Ficha da loja**
  - Nome: LisBora
  - Descrição curta (até 80 caracteres): "Rotas de bicicleta em Lisboa pelas ciclovias e ruas calmas, sem Internet."
  - Descrição completa: o primeiro parágrafo do [README](../README.md) serve de base. Junta o Descobrir (passeios automáticos para o tempo que tens), a navegação por voz com o ecrã desligado, o modo GIRA e a nota de segurança ("nenhuma rota é garantidamente segura").
  - Categoria: Mapas e navegação. Capturas de ecrã do telemóvel (pelo menos duas): o mapa com a rede ciclável, uma rota com alternativas, a navegação e o Descobrir.
  - Endereço de email de contacto: obrigatório e público. Escolhe um endereço que possas mostrar.
- **Política de privacidade**: o endereço acima.
- **Segurança dos dados**: a app não recolhe nem partilha dados. A localização é tratada só no telemóvel. Os pedidos ao GitHub (dados novos) e à plataforma da GIRA não levam dados pessoais, além do endereço IP de qualquer ligação. As ligações usam HTTPS. Não há contas, por isso não há pedido para apagar uma conta.
- **Serviços em primeiro plano**: declara o tipo `location` para a navegação passo a passo com o ecrã desligado, sempre com uma notificação visível e iniciada pelo utilizador ("Iniciar"). A Console pede um vídeo curto que mostre a funcionalidade.
- **Localização**: só em primeiro plano. A app não pede `ACCESS_BACKGROUND_LOCATION`, por isso não precisa da declaração de localização em segundo plano.
- **Acesso à app**: não precisa de conta nem de credenciais.
- **Anúncios**: não tem.
- **Classificação de conteúdo**: preenche o questionário. Não há conteúdo gerado por utilizadores nem compras.
- **Público-alvo**: não é dirigida a crianças.

## Antes de publicar

- O código ainda não tem licença (README). Não é preciso para a Play, mas convém decidir antes de divulgar.
- Os dados da GIRA vêm de uma API não documentada (exceção registada no `CLAUDE.md`): se deixar de responder, a app continua com as estações do pacote.
