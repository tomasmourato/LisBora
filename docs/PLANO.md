# LisBora — Plano de desenvolvimento

## Contexto

A LisBora é uma app Android para andar de bicicleta em Lisboa, com um mapa ao estilo do Google Maps e uma interface pensada para ciclistas. Faz duas coisas:
1. **Planear A→B**: o utilizador indica a partida e o destino e recebe rotas que privilegiam ciclovias e ruas calmas.
2. **Descobrir**: sem destino definido, a app gera uma rota interessante para um tempo disponível (por exemplo, 45 min), um motivo (lazer, paisagens, rio, desporto, subidas, descidas, família, cultura) e as preferências do utilizador.

Ponto de partida: a pasta `C:\Users\mrttm\projects\LisBora` só tem `claude/CLAUDE.md` ("Compila sempre a app e corre-a antes de finalizares a resposta"). Não é um repositório git. A máquina tem git, Node, Python e winget, mas **não tem JDK, SDK do Android, emulador nem Docker**. Tem 32 GB de RAM, um i7-13650HX e o hipervisor ativo.

Decisões tomadas contigo:
- **Tudo no telemóvel**: as rotas são calculadas no dispositivo e não há servidor.
- **Navegação v1**: seguir a rota no mapa, sem voz.
- **Ambiente**: instalo-o eu via winget, com a tua aprovação em cada instalação.
- **Contas**: não há. Os dados ficam todos locais.

### Dados verificados (rede ciclável da CML)
- Serviço ArcGIS: `https://services.arcgis.com/1dSrzEWVQn5kHHyK/arcgis/rest/services/Ciclovias/FeatureServer/0`. Licença **CC BY 4.0** (Município de Lisboa / Lisboa Aberta). Última edição a **23/10/2026**. Máximo de 2000 registos por pedido, por isso é preciso paginar. Pede-se com `outSR=4326`.
- Tem 1087 segmentos e cerca de 298 km, todos com `SITUACAO = Executado`. Os campos úteis são `TIPOLOGIA`, `NIVEL_SEGREGACAO`, `HIERARQUIA`, `DESIGNACAO`, `FREGUESIA`, `ANO` e `GlobalID`.

| Classe LisBora | TIPOLOGIA da CML (segregação) | km |
|---|---|---|
| `SEGREGADA` | Pista Ciclável Bi/Unidirecional e Contrassentido (separação física) | ~119 |
| `FAIXA` | Faixa Ciclável e Contrassentido (separação visual) | ~8 |
| `PARTILHADA_30` | 30+Bici e Zona de Coexistência | ~87 |
| `CICLOPEDONAL` | Percurso Ciclopedonal | ~24 |
| `TRILHO` | Trilho (Monsanto, etc.) | ~59 |

## Decisões técnicas (onde me afasto das sugestões do ChatGPT)

| Área | Escolha | Porquê |
|---|---|---|
| Motor de rotas | **Router próprio em Kotlin a correr no dispositivo**, num módulo JVM puro. O GraphHopper 11.1 só é usado **na construção dos dados**, no PC ou no CI. | Pediste "tudo no telemóvel". O GraphHopper deixou de suportar Android oficialmente depois da versão 1.0. Além disso, os modelos personalizados dele são compilados em tempo de execução com o **Janino** (bytecode JVM), o que não funciona em ART. Usá-lo na construção aproveita o parsing de OSM, que está muito testado (acessos, sentidos únicos incluindo `oneway:bicycle`, barreiras, piso), e a elevação. No telemóvel corre um A* simples sobre um grafo pequeno, só da região de Lisboa, que podemos controlar e testar por completo. |
| Ciclovias no encaminhamento | **Conflação ao nível de cada aresta** entre as linhas da CML e o grafo OSM, feita no pipeline. Cada aresta fica com a classe de infraestrutura da tabela acima. | Não dependemos do OSM estar completo. O mapa e o router usam **a mesma versão** dos dados da CML. |
| Mapa | MapLibre Android 13.x (versão estável fixada) dentro de `AndroidView`, com um extrato **Protomaps PMTiles** offline (`pmtiles://file://`, suportado desde a 11.7.0) e um estilo próprio para ciclismo. | Não precisa de chaves nem de servidor e funciona offline. O `maplibre-compose` ainda está na 0.x e a API não é estável. |
| Pesquisa de moradas | Índice **offline próprio** (`search.lbs`, prefixos de palavras), gerado a partir do OSM (ruas, números de porta, POIs, freguesias). Na Fase 3 substituiu o SQLite FTS4 previsto: o mesmo código Kotlin normaliza o texto no pipeline e na app, testa-se na JVM e ordena por proximidade sem depender do tokenizador do SQLite de cada telemóvel. | A instância pública do Photon é "só para demonstração". Assim não há API nem chaves. |
| Preferências | **DataStore**, não Room | Room fica reservado para favoritos, histórico e rotas guardadas. |
| Rede | Só **OkHttp**, para descarregar pacotes de dados. Não uso Retrofit. | Não há API REST. |
| Backend | **Nenhum**. Os pacotes de dados são estáticos e versionados, alojados em GitHub Releases (ou Cloudflare R2). | Custo zero e nenhuma infraestrutura para manter. |
| Supabase | Fica fora da v1. | Decidido contigo. |
| Mantém-se | Kotlin, Compose + Material 3, MVVM/UDF com `StateFlow`, Coroutines/Flow, Hilt (KSP), Room, JUnit, testes de UI do Compose | — |

Versões: na implementação vou buscar a última estável nas páginas oficiais e fixo-a em `gradle/libs.versions.toml`. Referências: JDK 21, GraphHopper 11.1 (precisa de Java 17 ou superior; o master já exige Java 25, por isso não o usamos) e MapLibre Android 13.x. Se o renderer Vulkan der problemas no emulador, mudo para o artefacto OpenGL.

## Arquitetura

```
[pipeline (JVM, no PC/CI)]
  OSM Portugal (Geofabrik) + Rede Ciclável CML (ArcGIS) + elevação + region.geojson
    → GraphHopper import (perfil bike, elevação)
    → exportação do grafo da região (formato binário próprio)
    → conflação CML→arestas, elétricos, piso, stress, atributos "cénicos"
    → POIs, índice de pesquisa, ciclovias.geojson, basemap.pmtiles
    → relatório QA + rotas de referência → datapack vN + manifest.json
           │  (estático, versionado, sha256)
           ▼
[app Android]  DataPackRepository → GraphStore(:core:routing) → RoutingEngine / DiscoveryEngine
               MapLibre (basemap + ciclovias + rota) · Room · DataStore · Fused Location
```

### Estrutura do repositório (um único build Gradle)
- `:core:routing` — Kotlin/JVM puro, sem Android. Inclui:
  - formato do grafo (leitor e escritor)
  - índice espacial
  - A* sobre arestas
  - pesagem e modelo de ETA
  - alternativas
  - `DiscoveryEngine`
  - `InstructionBuilder` em pt-PT

  É partilhado pela app e pelo pipeline.
- `:pipeline` — aplicação CLI em Kotlin/JVM que depende do `graphhopper-core` 11.1 e do JTS. Configuração em `pipeline/config/`: `golden-routes.json` (rotas de referência), `golden-searches.json` (pesquisas de referência) e `golden-discoveries.json` (descobertas de referência). Previstos: `region.geojson` (hoje a região é o retângulo `ServiceArea`) e `overrides.geojson` (correções manuais).
- `:app` — Android, com pacotes por funcionalidade:
  - `feature/map`, `feature/search`, `feature/planner`, `feature/discover`, `feature/navigation`, `feature/saved`, `feature/settings`, `feature/about`
  - `data/` com os repositórios
  - `di/` com os módulos Hilt
  - `ui/theme`

Ficheiros principais a criar (representativos):
- `settings.gradle.kts`, `gradle/libs.versions.toml`
- `core/routing/src/main/kotlin/pt/lisbora/routing/{graph/GraphFormat.kt, graph/GraphStore.kt, RoutingEngine.kt, weighting/BikeWeighting.kt, eta/RiderModel.kt, discover/DiscoveryEngine.kt, instructions/InstructionBuilder.kt}`
- `pipeline/src/main/kotlin/pt/lisbora/pipeline/{FetchCml.kt, ImportOsm.kt, ExportGraph.kt, Conflate.kt, BuildPois.kt, BuildSearchIndex.kt, QaReport.kt, PackDataset.kt}`
- `app/src/main/java/pt/lisbora/{MainActivity.kt, data/DataPackRepository.kt, data/RoutingRepository.kt, feature/map/LisboraMap.kt, ...}`
- `CLAUDE.md` passa para a raiz (hoje está em `claude/CLAUDE.md`, que não é carregado automaticamente). Fica com a regra "compilar e correr" e as convenções do projeto.

## Dados e pipeline

1. **Região**: `region.geojson` cobre o concelho de Lisboa, uma margem de cerca de 3 km (Algés, Oeiras ribeirinha, Amadora, Odivelas e Loures sul) e Cacilhas/Almada ribeirinha (ligação por ferry). A região é configurável.
2. **OSM**: extrato de Portugal da Geofabrik, importado pelo GraphHopper com o perfil bike e `turn_costs`. A exportação guarda só as arestas dentro da região e o maior componente fortemente ligado.
3. **Elevação**: o GraphHopper 11.1 não tem o fornecedor `pmtiles`, por isso a elevação é calculada na exportação, só para a região, a partir dos mosaicos Terrarium do **Mapterhorn** (z14, ~3,7 m por píxel). Em Portugal continental vêm do modelo digital do **terreno** LiDAR da DGT (0,5 m, CC BY 4.0), por isso as ruas são medidas no chão e não nos telhados (o SRTM e o Copernicus são modelos de superfície). Cada nó usa a mediana de 9 amostras; cada aresta é amostrada a cada 8 m, limpa com uma mediana móvel de 48 m (remove escavações e rampas de garagem à beira da rua) e suavizada com uma média de 24 m. Pontes, túneis e barcos são interpolados a partir das ruas à volta. O relatório QA lista as arestas pedaláveis acima de 25%.
4. **Conflação CML** (dentro do `ExportGraph`, sem plugins no GraphHopper):
   - Amostrar as linhas da CML a cada ~5 m.
   - Atribuir cada amostra **apenas à aresta mais próxima** dentro de ~12 m e com rumo compatível (menos de 30°). Dar prioridade a vias OSM próprias para bicicleta. Isto evita marcar a estrada paralela a uma pista separada.
   - Uma aresta fica com a classe da CML se tiver 60% ou mais de cobertura. Ciclovias OSM que não estão na CML ficam como `OSM_CICLOVIA`.
   - O `overrides.geojson` permite correções manuais.
   - O contrassentido **não altera acessos** automaticamente. As divergências com o `oneway:bicycle` do OSM vão para o relatório e só entram por override manual.
5. **Atributos Lisboa por aresta**:
   - carris de elétrico (correspondência espacial com `railway=tram`)
   - calçada (`surface=sett/cobblestone`)
   - declive médio e máximo
   - escadas e desmontar (`get_off_bike`)
   - stress de tráfego de 1 a 4, calculado com `road_class`, `maxspeed`, `lanes`, infraestrutura e elétrico
   - flags cénicas: junto ao rio, em parque ou zona verde, perto de miradouro
   - nome da rua
   - semáforos, para o atraso no ETA
6. **POIs** (OSM): miradouros, parques, bebedouros, estacionamento de bicicletas, oficinas e estações GIRA (`amenity=bicycle_rental`), monumentos.
7. **Pesquisa**: `search.lbs`, um índice binário próprio (ver a tabela de decisões): palavras normalizadas sem acentos, prefixos, abreviaturas ("r.", "lg", "av"), palavras genéricas ("estação", "metro", "barco") e nomes alternativos do OSM. O contexto ("Arroios, Lisboa") vem das fronteiras de freguesias e concelhos. Pesquisas de referência em `pipeline/config/golden-searches.json`.
8. **Mapa base**: `pmtiles extract` do build diário do Protomaps para o bbox da região. O estilo é gerado com `@protomaps/basemaps`, com fontes e sprites incluídos e versões claro e escuro.
9. **Relatório QA**, que falha o build se piorar:
   - % de km da CML associados a arestas (meta inicial de 95% ou mais, ajustada depois da primeira medição)
   - GeoJSON das falhas
   - diferenças face à versão anterior da CML (km novos ou removidos)
   - resultado das rotas de referência
10. **Pacote de dados**:
    - Ficheiros: `graph.lbg`, `ciclovias.geojson`, `pois.geojson`, `search.db`, `basemap.pmtiles`, `attribution.json`.
    - O `manifest.json` leva `dataVersion`, `formatVersion`, sha256, tamanhos e as datas das fontes.
    - O pacote de base vem embutido na app. As atualizações chegam por WorkManager (só em Wi-Fi): a app verifica o sha256, troca de pasta de forma atómica e mantém a versão anterior para poder voltar atrás. **A rede ciclável atualiza-se sem publicar uma nova versão da app.**

## Encaminhamento no dispositivo (`:core:routing`)

- **Grafo**:
  - Binário little-endian com cabeçalho (`magic`, `formatVersion`, `dataVersion`, bbox).
  - Nós: coordenadas em int E7 e elevação.
  - Arestas: atributos compactados, geometria e índice de nomes, com adjacência CSR.
  - Inclui uma grelha espacial para o snapping e as restrições de viragem.
  - Carrega-se para arrays primitivos em menos de 200 ms (a confirmar).
- **Algoritmo**:
  - **A* sobre arestas** (edge-based). Assim respeita restrições de viragem e penaliza viragens à esquerda em vias movimentadas e inversões de marcha.
  - A heurística é a distância em linha reta vezes o custo mínimo por metro, o que a mantém admissível. Os multiplicadores de bónus têm um limite mínimo.
  - Os testes comparam o A* com o Dijkstra em grafos aleatórios.
- **Custo de uma aresta** = tempo (modelo físico) × fator de conforto × fator do motivo + penalização de viragem. Valores iniciais, a calibrar com as rotas de referência:
  - SEGREGADA ×0,75; FAIXA ×0,9; PARTILHADA_30 ×0,95; CICLOPEDONAL ×0,9 em lazer e ×1,2 em desporto; TRILHO depende do tipo de bicicleta
  - via principal sem infraestrutura ×1,6 a ×2,5; carris de elétrico ×1,5; calçada ×1,3 a ×2 (preferência)
  - declive acima do máximo escolhido: penalização forte; desmontar: velocidade a pé e ×4; escadas: excluídas
- **ETA físico** (essencial nas colinas):
  - Potência conforme o nível do ciclista (descontraído, médio ou desportivo) e o tipo de bicicleta (cidade, estrada, BTT/gravel, e-bike/GIRA com assistência até 25 km/h). Resistência ao rolamento conforme o piso, massa, CdA e declive.
  - Velocidade máxima a descer reduzida em calçada, elétrico e ciclopedonal. Atraso em semáforos.
  - Fator pessoal calibrado com as voltas feitas.
- **Alternativas**: método de penalização (penalizar as arestas já usadas e voltar a calcular). Ficam no máximo 3 rotas, distintas pelo índice de Jaccard.
- **Instruções pt-PT**: "Vire à esquerda para a Rua X", "Entre na ciclovia", "Saia da ciclovia", "Desmonte: escadas", "Subida de 9% nos próximos 200 m".
- **Segurança**: a app **nunca diz que uma rota é "segura"**. Mostra o stress, a % por tipo de infraestrutura e os perigos (carris de elétrico, calçada, descidas íngremes, troços sem infraestrutura com stress 4), com um aviso discreto.

### Descobrir (`DiscoveryEngine`)
- **Entradas**:
  - partida (GPS ou ponto escolhido)
  - chegada opcional: volta circular ou A→B com tempo disponível
  - duração, motivo e restrições: evitar calçada, evitar elétricos, inclinação máxima, só segregada quando possível
  - tipo de bicicleta e nível do ciclista
- **Passos**:
  1. Calcular a distância-alvo a partir do tempo, usando o modelo de ETA, e refiná-la iterativamente.
  2. Gerar 12 a 30 candidatas, com um orçamento de cerca de 1–2 s no dispositivo:
     - **com âncoras**: POIs adequados ao motivo dentro da elipse alcançável, em combinações de 1 a 3 por beam search, com penalização de reutilização de arestas para evitar ida e volta pelo mesmo caminho
     - **voltas geométricas**: 2 ou 3 pontos virtuais em polígono, com rumo e semente variáveis
  3. Pontuar cada candidata por:
     - desvio face ao tempo pedido
     - % de infraestrutura e stress
     - pontuação cénica: km junto ao rio ou em parques e âncoras visitadas
     - perfil de elevação ajustado ao motivo
     - repetição de troços e perigos
  4. Mostrar as 3 mais diversas, com chips do tipo "porquê esta rota" (ex.: "68% em ciclovia segregada · 2 miradouros · +210 m · 47 min").
  5. Botão "Outra rota" (nova semente) e ajustes rápidos ("mais rio", "menos subidas").
- **Descidas**: dá prioridade a A→B que acabem mais abaixo (ex.: Graça → rio), a subidas em declive suave e a descidas em asfalto ou ciclovia. Evita descidas íngremes em calçada ou com carris de elétrico.

## App Android — UX

- **Ecrã principal**:
  - mapa claro centrado em Lisboa, com a rede ciclável colorida por classe e uma legenda
  - barra "Para onde vais?" e um FAB grande "Descobrir"
  - camadas opcionais: POIs, GIRA, bebedouros, estacionamento
  - tocar num troço da CML mostra a designação e a tipologia
- **Rota**:
  - bottom sheet com cartões (tempo, distância, D+/D−, % por infraestrutura, stress)
  - linha da rota colorida por infraestrutura, declive ou stress (alternável)
  - perfil de elevação em Compose Canvas, com os perigos marcados
  - folha de preferências
- **Seguir rota (v1)**:
  - posição Fused a 1 Hz, com snapping à rota
  - próxima manobra em letra grande e "o que vem aí" (subida de X% em Y m)
  - fora da rota (mais de 40 m durante mais de 5 s) → recálculo local imediato
  - ecrã sempre ligado e resumo no fim
  - voz e serviço em primeiro plano ficam para mais tarde
- **Guardados**: favoritos (Casa/Trabalho), histórico de pesquisas e rotas guardadas (Room), exportação e importação de GPX.
- **Definições**: tipo de bicicleta, nível, preferências por omissão e "Dados atualizados em…".
- **Sobre**: atribuições e licenças (AboutLibraries).
- **Geral**: pt-PT com strings externalizadas, modo escuro e acessibilidade (alvos grandes, contraste). A localização é processada só no dispositivo.

## Fases

Cada fase termina com build, testes, instalação e execução no emulador e uma verificação visual por screenshot, conforme o CLAUDE.md.

0. **Ambiente e esqueleto**
   - Instalar via winget o JDK 21 e o Android Studio, depois o SDK, o emulador e uma imagem x86_64 através do `sdkmanager`. **A aceitação das licenças do SDK fica contigo.** Se o emulador pedir a Windows Hypervisor Platform, és tu que a ativas.
   - `git init` e `.gitignore`.
   - Build multi-módulo e version catalog. App vazia com Hilt, Compose M3 e o tema LisBora a correr no emulador. `CLAUDE.md` passa para a raiz.
1. **Mapa e ciclovias**
   - `pipeline fetch-cml`: paginação, normalização em classes e `ciclovias.geojson`.
   - Extrato PMTiles e estilo.
   - MapLibre com o mapa base offline, a camada de ciclovias com legenda e informação ao toque, a localização do utilizador e as atribuições.
   - Primeiro passo de verificação: confirmar `pmtiles://file://` no emulador. Se falhar, uso MBTiles ou OpenFreeMap online como recurso temporário.
2. **Grafo e router (JVM)**
   - `pipeline build`: importação com GraphHopper, elevação, exportação, conflação, atributos e relatório QA.
   - `:core:routing` com o A* sobre arestas, a pesagem por omissão e o ETA.
   - Testes unitários e **rotas de referência no grafo real**, entre elas:
     - Cais do Sodré → Belém usa 80% ou mais de ciclovia ribeirinha
     - Marquês → Saldanha usa a pista da Duque de Ávila / Fontes Pereira de Melo
     - nunca usa a Ponte 25 de Abril nem escadas
     - Baixa → Graça com inclinação máxima de 8% evita a Calçada da Glória
   - **Feito (25/10/2026).** `pipeline build` importa Portugal com o GraphHopper (~1 min, guardado em cache), recorta a região, mantém o maior componente fortemente ligado e escreve `graph.lbg` (8 MB: 133 mil nós, 181 mil arestas, 3,4 mil restrições de viragem) só se o QA passar. A conflação associa 94,5% dos 297 km da CML (mínimo exigido: 85%). As rotas de referência estão em `pipeline/config/golden-routes.json`; a verificação da Calçada da Glória faz-se em Restauradores → São Pedro de Alcântara (a Glória não fica entre a Baixa e a Graça), e Praça da Figueira → Largo da Graça verifica a duração. O grafo ainda não vai na app: entra no pacote embutido na Fase 3.
3. **Planear A→B**
   - Pacote de dados embutido e `RoutingRepository` (`Dispatchers.Default`).
   - Pesquisa offline e pressão longa no mapa.
   - Até 3 alternativas, cartões, perfil de elevação, perigos e preferências (DataStore).
   - Favoritos e histórico (Room).
   - **Feito (26/10/2026).**
     - O pacote embutido passa a levar `graph.lbg` (8 MB) e `search.lbs` (4,8 MB, 78 mil entradas: 13 mil ruas, 26 mil locais, 38 mil moradas e 630 localidades). `pipeline search` constrói o índice em ~30 s e só o escreve se as 12 pesquisas de referência passarem.
     - Alternativas pelo método de penalização (até 2, no máximo 30% mais caras e com sobreposição de Jaccard ≤ 0,6; respeitam a inclinação máxima quando a melhor rota a respeita). A app mostra a melhor rota primeiro e as alternativas logo a seguir. No emulador, com o build de debug, a melhor rota de Chiado a Belém demora ~0,75 s e as três ~1,6 s.
     - Perigos por rota: carris de elétrico, calçada, trânsito intenso sem ciclovia, subidas e descidas de 8% ou mais em 50 m, troços a pé e barco. Cartões com tempo, distância, subida, % de infraestrutura e rótulos (Recomendada, Mais rápida, Mais ciclovia, Menos subida). Detalhes com infraestrutura, stress, perfil de elevação interativo e lista de perigos. A rota pode ser colorida por infraestrutura, declive ou stress.
     - Pesquisa, cartão de local, pressão longa (nome da morada, do local ou da rua mais próxima) e "escolher no mapa". Preferências em DataStore; Casa, Trabalho, favoritos e recentes em Room. Tudo fica no telemóvel.
     - Descidas (26/10/2026): o custo de descer em calçada ou carris cresce mais depressa do que a inclinação (×1,6 a 9%, ×14 a 18%); em asfalto só acima de 12% e de leve. Acima de 12% em calçada ou carris conta-se no máximo como descer a pé, o ETA usa esse tempo e a app avisa "melhor a pé". Numa amostra de 300 viagens no centro, as rotas com descidas em calçada ou carris acima de 12% passaram de 11% para 6% (acima de 16%: de 3% para 1%), sem mudar o tempo médio. Rotas de referência novas: Camões → Cais do Sodré (14% → 7,6%), Príncipe Real → Avenida da Liberdade (9,6% → 4,1%) e Graça → Praça da Figueira (20,3% → 12,9%, +4 min). De São Pedro de Alcântara a Glória continua a ser o caminho, porque as outras saídas obrigam a andar a pé contra o sentido, mas passa a ser feita a pé.
4. **Descobrir**
   - UI (duração, motivo, circular ou A→B), `DiscoveryEngine`, chips explicativos, "Outra rota" e guardar.
   - Camada de POIs.
   - **Feito (26/10/2026).**
     - Atributos cénicos no grafo (`build`): uma aresta fica "junto ao rio" se pelo menos metade dela está a menos de 70 m da margem (linha de costa do Tejo e docas e lagos largos), "verde" se pelo menos metade está dentro de um parque, jardim ou mata, e "miradouro" se passa a menos de 40 m de um `tourism=viewpoint`. Resultado: 129 km junto ao rio, 1029 km verdes (a maior parte em Monsanto e na margem da região) e 1194 arestas junto a miradouros; o QA escreve `qa/scenery.geojson` para ver no mapa.
     - `pois.geojson` (`search`, 0,7 MB): 5149 pontos de interesse do OSM sem duplicados (58 miradouros, 537 parques e jardins, 785 parques infantis, 128 museus, 672 monumentos, 212 atrações, 21 praias, 614 bebedouros, 1860 estacionamentos de bicicletas, 208 estações GIRA, 44 lojas e 10 estações de reparação).
     - `DiscoveryEngine`: o tempo passa a uma distância em linha reta medida em 4 voltas de calibração à volta da partida; depois ~20 candidatas (passeios por 1 a 3 POIs ou pontos cénicos escolhidos por beam search, e voltas ou elipses com pontos virtuais; um ponto virtual fora da rede, no rio ou fora da região, é puxado para dentro). Cada candidata é calculada com `routeVia` (A* com peso 1,5, sem inversões nas paragens e com as ruas já feitas 1,3 a 2,5 vezes mais caras), as que falham o tempo são redimensionadas uma vez, e ficam as 3 melhores com sobreposição de Jaccard ≤ 0,5. O motivo muda os pesos do router (`RouteBias`: rio, verde, miradouros, subidas), as restrições (família e descidas: máx. 6%, sem calçada) e a pontuação.
     - Descobertas de referência em `pipeline/config/golden-discoveries.json` (`pipeline discover`): as 10 passam. Numa amostra de 200 descobertas aleatórias no centro, a melhor rota fica a ±12% do tempo pedido em 100% dos casos (erro médio 3%) e repete 5% do percurso; o cálculo demora 0,26 s de mediana no PC com 4 fios (0,77 s com 1). No emulador, com o build de debug, demora 0,8 a 3 s com 3 fios, e o motor fica pronto em ~1,1 s ao abrir o Descobrir.
     - App: o botão "Descobrir" abre as escolhas (volta ou até um destino, 20 min a 2 h, 8 motivos), a partir da localização, de uma pesquisa ou de uma pressão longa. Cada rota mostra até 3 razões ("8,5 km junto ao rio", "2 miradouros", "55% em ciclovia segregada", "Quase plana"…), os locais por onde passa (com nome no mapa) e os mesmos detalhes do A→B. "Outra rota" usa outra semente e evita as rotas já mostradas; "Mais rio" e "Menos subidas" ajustam o motivo. As rotas guardadas ficam no Room (só as paragens, o motivo e os ajustes) e são calculadas de novo com os dados do dia.
     - Camada de POIs na legenda, com quatro interruptores: miradouros, jardins e museus (com nome a partir do zoom 14,5), bebedouros, estacionamento de bicicletas, e GIRA e oficinas.
     - Opção "Bicicleta GIRA": os termos da GIRA (cláusula 4(o)) só deixam circular dentro do concelho de Lisboa. O grafo marca as arestas do concelho (fronteira administrativa do OSM, `admin_level=7`; 2992 km), e com `stayInLisbon` o router não usa as outras nem o Descobrir faz paragens fora. A rota começa na estação GIRA mais próxima da partida (a até 1,5 km a pé) e acaba na mesma ou, até um destino, na estação mais próxima dele; usa o modelo de bicicleta elétrica. As ~196 estações vêm do OSM, no `pois.geojson`. As rotas com bicicleta própria continuam a poder sair do concelho.
     - Disponibilidade GIRA em tempo real: o feed aberto da EMEL (`opendata.emel.pt`) está em baixo. Desde 2026 a GIRA usa a plataforma VAIMOO, e a app oficial lê bicicletas e docas de uma base Firestore com a chave dessa app (203 estações). Por decisão do utilizador, a LisBora lê a mesma base (`VaimooGiraFeed`, com a exceção registada no `CLAUDE.md`), só no modo GIRA, guardando os dados durante 1 minuto. A estação de partida é a mais próxima com bicicletas e em serviço; a de chegada, a mais próxima do destino com docas livres. O mapa mostra a cinzento as estações sem bicicletas, e os resultados dizem quantas bicicletas e docas há. Sem rede (ou se a EMEL fechar a API) usa os últimos dados em direto ou as estações do pacote, e avisa que não há dados em tempo real. A app passou a pedir a permissão de Internet só para isto.
5. **Seguir rota**
   - Modo navegação, instruções pt-PT, recálculo e resumo.
   - GPX e calibração pessoal do ETA.
   - **Feito (26/10/2026).**
     - Núcleo (`pt.lisbora.routing.navigation`): instruções estruturadas a partir da rota (viragens com o ângulo medido 15 m antes e depois do cruzamento, rotundas com a rua de saída, barco, mudança de nome, entrar e sair da ciclovia, desmontar e voltar a pedalar; desvios curtos num cruzamento e viragens nos primeiros 15 m juntam-se). O texto em pt-PT fica na app ("Vire à esquerda e entre na ciclovia", com a rua numa linha à parte). `RouteTracker` projeta cada posição no troço logo atrás e à frente da anterior, por isso segue por ordem as voltas e as idas e voltas pela mesma rua; o rumo do GPS separa os dois sentidos. Fora da rota: mais de 40 m (mais a imprecisão do GPS) durante 5 s. `RouteSplice` corta e junta rotas, para voltar a uma rota do Descobrir 250 m à frente e manter o resto. GPX 1.1 (escrita e leitura).
     - App: botão "Iniciar" no A→B e no Descobrir; localização a 1 Hz (GPS do sistema, sem serviços Google); a câmara segue o ciclista na direção do movimento, com "Recentrar"; ecrã sempre ligado. Mostra a próxima manobra em letra grande com a distância arredondada, a seguinte quando está a menos de 80 m, o que vem aí a menos de 300 m ("Subida de 9% daqui a 150 m", "Calçada…", "Carris…"), o tempo e a distância que faltam e a hora de chegada. O recálculo é feito no telemóvel: no A→B, uma rota nova até ao destino; no Descobrir, o regresso à rota planeada. No fim, um resumo (distância, tempo a pedalar, velocidade média, subida), com exportação para GPX (percurso feito e rota planeada). As voltas com bicicleta própria de 2 km e 10 min ou mais ajustam o ritmo pessoal do ETA (30% do caminho entre o previsto e o real, entre 0,6 e 1,6), que se pode repor nas preferências. Os saltos do GPS não contam como distância.
     - GIRA: a contagem dos 45 min começa na estação de levantamento (antes disso a app diz "Vai até à estação GIRA X e levanta uma bicicleta" e não recalcula a rota). Aos 10 e 5 min do fim, e depois de passarem, avisa, a menos que a viagem acabe a tempo, e diz qual é a estação mais próxima naquele momento com doca livre (e, de preferência, bicicletas), com as contagens em direto. "Ir até lá" calcula o desvio até lá; "Já troquei" recomeça os 45 min e volta à rota planeada.
     - Verificação: `pipeline route` escreve uma rota em GPX e `pipeline/tools/simulate-ride.py` reprodu-la como GPS no emulador. Testado: manobras, aviso de calçada, sair da ciclovia, recálculo em 78 ms depois de 5 s fora da rota, chegada, resumo e GPX guardado; o início de uma volta GIRA (a caminho da estação e a contagem a começar lá). O aviso dos 45 min está coberto por testes unitários (no emulador seria preciso esperar 35 min).
     - Importar GPX ("Importar GPX" no Descobrir): o ficheiro é lido (pontos do percurso, da rota ou de passagem, de qualquer app), reduzido a pontos a cada 250 m mais os pontos onde o percurso se afasta mais de 25 m da linha entre dois (esquinas, inversões), e seguido nas ruas do grafo através deles, podendo voltar para trás onde o percurso volta (`TrackMatcher`). Os pontos a mais de 40 m de qualquer rua são ignorados. Aparece como uma rota do Descobrir, com o nome do GPX, as razões ("623 m junto ao rio") e "Iniciar"; a navegação retoma o percurso do GPX depois de um desvio. No emulador, um GPX de 283 pontos foi seguido em 116 ms. Ficheiros vazios, fora da área ou sem caminho dão uma mensagem própria.
     - A voz e o serviço em primeiro plano ficam para a Fase 7.
6. **Atualização de dados sem nova versão da app**
   - `manifest.json` e pacotes versionados (GitHub Releases num repositório público, a decidir nessa altura).
   - Atualização por WorkManager com sha256, troca atómica e reversão.
   - GitHub Actions semanal: corre o pipeline e os testes de qualidade e publica só se houver mudanças e o QA passar.
   - Pedido do utilizador: atualizar também os dados da GIRA.
   - **Feito (26/10/2026).**
     - Onde: o release `datapack` (pré-lançamento) do próprio repositório. O `manifest.json` é sempre o mesmo endereço; cada ficheiro tem o nome do conteúdo (`graph.faab832ca7ada312.lbg`), por isso um ficheiro publicado nunca muda e só se envia o que mudou. Ficam os ficheiros do pacote atual e do anterior (para telemóveis a meio de uma descarga); os outros são apagados.
     - Manifesto: `dataVersion` (quando o conteúdo mudou pela última vez; só muda quando muda algum ficheiro), `formats` (versões do `graph.lbg` e do `search.lbs`, lidas do mesmo código do `:core:routing`) e, nos publicados, o `url` de cada ficheiro. O grafo e o índice levam por dentro a data da fonte mais recente (OSM, CML) e não a hora do build: dois builds com as mesmas fontes dão os mesmos bytes (verificado com dois builds seguidos do grafo e do índice), por isso uma semana sem mudanças não publica nada.
     - Pipeline: `gira` (lista das estações da EMEL), `pull` (descarrega o pacote publicado) e `release` (prepara só os ficheiros novos e o manifesto; nada, se só mudaram as datas dos `*.meta.json`).
     - Workflow `.github/workflows/data.yml`, às segundas às 04:30 UTC e a pedido: testes do router e do pipeline, `pull`, `fetch-cml` e `gira` (se falharem, fica o que o pacote tem), `build` (QA e rotas de referência), `search` (pesquisas de referência), `discover` (descobertas de referência), mapa base na primeira segunda-feira do mês ou a pedido, `release` e publicação (ficheiros primeiro, manifesto no fim). O relatório QA fica como artefacto. Corre só o pipeline (`-Plisbora.semApp=true`), sem o Android SDK.
     - App: os ficheiros ficam em `filesDir/datapack/objects`, uma vez cada, com o nome do conteúdo, e o `state.json` guarda o pacote em uso, o anterior, um descarregado à espera e as versões recusadas; mudar de pacote é escrever esse ficheiro. O `DataPackUpdater` lê o manifesto, descarrega só o que falta (retoma descargas cortadas), confere tamanho e SHA-256 e deixa o pacote para o próximo arranque: o mapa, o router e a pesquisa usam sempre a mesma versão. Ignora pacotes não mais recentes, recusados ou em formatos que a app não lê ("precisa de uma versão mais recente da app"); um 404 (nada publicado) não é erro. WorkManager uma vez por dia, só em Wi-Fi, com bateria e espaço; nas preferências, "Dados do mapa" mostra a versão e as datas das fontes (rede da CML, OpenStreetMap, estações GIRA), "Procurar dados novos" (em qualquer rede) e, com dados novos prontos, "Reiniciar agora". Uma nova versão da app com dados mais recentes do que os descarregados passa a usar os seus.
     - Reversão: se o grafo, o índice ou o mapa de um pacote atualizado não se lerem, o arranque seguinte volta ao anterior e essa versão nunca mais é instalada.
     - GIRA: `gira.geojson` (202 estações com número, nome e docas, ~43 KB) vem da mesma base da app oficial e entra no pacote embutido e em cada atualização. Substitui as estações do OSM no modo GIRA sem rede e na camada "GIRA e oficinas" do mapa. As contagens em tempo real continuam a vir do `VaimooGiraFeed`.
     - Verificação no emulador, com um servidor local (`adb reverse`; o Android 17 não deixa as apps chegar ao 10.0.2.2 sem a permissão de rede local): a app migrou do formato antigo, descarregou só o grafo e o seu `meta` (8,2 MB) entre 11 ficheiros, mostrou "Dados novos… prontos" e, depois de "Reiniciar agora", passou a usar o grafo novo. Com o grafo em uso estragado, marcou o pacote, voltou ao anterior no arranque seguinte e recusou a versão. O fluxo do workflow (`pull`, `gira`, `release` sem mudanças) correu localmente; a primeira publicação no GitHub fica para quando o workflow estiver na `main`.
7. **Polimento**
   - Voz (TTS) e serviço em primeiro plano com o ecrã desligado.
   - Desempenho (perfil de arranque, tempo de rota), acessibilidade e preparação para a Play Store (política de privacidade).
   - **Feito (26/10/2026).**
     - Volta com o ecrã desligado: o estado da navegação passou do ecrã para a `NavigationSession` (singleton no scope da app); o `NavigationViewModel` é só uma camada por cima. O `NavigationService`, um serviço em primeiro plano do tipo localização iniciado com "Iniciar", mantém a volta com o ecrã desligado ou a app em segundo plano. A notificação mostra a próxima indicação, a rua, o tempo que falta e a hora de chegada, e tem o botão "Terminar". O serviço para sozinho no fim. A app pede a permissão de notificações na primeira volta (Android 13 ou mais recente); sem ela, ou sem localização em primeiro plano, a volta continua com o ecrã ligado.
     - Voz: o `VoicePrompts` decide o que dizer e tem testes unitários.
       - A próxima manobra é dita ~150 m antes e outra vez a 40 m, com a seguinte quando vem logo a seguir.
       - Também diz os perigos a 150 m, a saída da rota, os avisos da GIRA e a chegada.
       - Uma rua que só muda de nome aparece no ecrã mas não se diz.

       O `VoiceGuide` usa o TTS do telemóvel em pt-PT (pt-BR, se for o único que existe) e baixa o volume da música enquanto fala. O botão de som da navegação liga e desliga a voz, e a escolha fica nas preferências. Sem voz em português, a app avisa. No emulador, uma volta simulada confirmou que, com o ecrã desligado, a notificação e a voz continuam. Na chegada, a voz disse "Chegaste ao destino.", o serviço parou e a notificação desapareceu. "Terminar", na notificação, acaba a volta.
     - "Sobre a LisBora" abre-se nas preferências; a barra "Para onde vais?" ganhou um botão para elas. Mostra a versão, o que a app faz com os dados, a [política de privacidade](PRIVACIDADE.md), as fontes dos dados com as licenças e as licenças de código aberto completas (`assets/licenses/NOTICES.txt`). A cópia de segurança do Android leva os favoritos, as rotas guardadas e as preferências, mas não os pacotes de dados (~35 MB, acima do limite de 25 MB).
     - Acessibilidade: os testes de UI correm com as verificações do Accessibility Test Framework (rótulos, tamanho dos alvos, contraste) em cada ação. A única falha encontrada foi uma linha vazia do perfil de elevação, que o leitor de ecrã anunciava como texto.
     - Desempenho:
       - O release usa R8 e redução de recursos. O APK tem 78 MB: ~35 MB de dados, e quase todo o resto são as bibliotecas nativas do MapLibre. As bibliotecas de 64 bits estão alinhadas a 16 KB.
       - O perfil de arranque (Baseline Profile) está em `app/src/main/baseline-prof.txt` e é instalado pelo `profileinstaller`. Foi gravado pelo módulo `:baselineprofile` com o arranque, uma pesquisa e o Descobrir. No emulador, o arranque a frio até ao primeiro ecrã (8 arranques de cada) demorou 1989 ms de mediana sem o perfil e 1932 ms com ele. A diferença fica dentro da variação (1,5 a 2,7 s), porque no emulador o código corre no processador do PC. O ganho real mede-se num telemóvel com o mesmo comando (`StartupBenchmark`).
       - No release, no emulador, a melhor rota de Chiado a Belém demora 380 ms e as três rotas 840 ms. No debug da Fase 3 eram ~750 ms e ~1,6 s.
     - Play Store:
       - A chave de assinatura do release é lida de `~/.gradle/gradle.properties`, nunca do repositório. Sem ela, o release é assinado com a chave de debug.
       - O guia [PLAY_STORE.md](PLAY_STORE.md) diz o que falta fazer na Play Console.
       - A atualização de dados a partir do GitHub foi verificada no release: o worker descarregou o pacote que o workflow publicou e, depois de "Reiniciar agora", a app passou a usar o grafo novo.

Para o futuro, opcional:
- Supabase (sincronização e partilha)
- correções de contrassentido com lista manual
- "pôr do sol nos miradouros"
- relatórios de perigos

## Verificação

- `gradlew :core:routing:test :pipeline:test`: testes de grafos sintéticos (sentido único, acessos, restrições, A* = Dijkstra), escrita e leitura do formato, conflação em fixtures (pista paralela à estrada → só a pista fica marcada) e ETA.
- `gradlew :pipeline:run --args="build"`: gera o `build/datapack/` e `qa/report.md` com a cobertura da CML e o GeoJSON das falhas, para ver no geojson.io ou no QGIS.
- `gradlew :pipeline:goldenRoutes`: as rotas de referência têm de passar. O mesmo teste corre no CI antes de cada publicação.
- `gradlew :app:testDebugUnitTest`: ViewModels com Turbine e repositórios falsos.
- `gradlew :app:connectedDebugAndroidTest`: testes de UI em Compose para pesquisa → rota → seguir e para Descobrir, mais os DAOs do Room.
- No emulador: `assembleDebug`, `adb install`, `am start` e screenshot. O GPS é simulado ao longo da rota (script com `adb emu geo fix` ou reprodução de GPX) para testar o snapping, o fora-da-rota e o recálculo.
- Revisão manual por fase: as ciclovias desenhadas coincidem com as usadas pelo router (mesmo `dataVersion`), e as rotas e os perfis de elevação fazem sentido nos bairros com colinas.

## Licenças e atribuições

- **OSM (ODbL)**: "© OpenStreetMap contributors" no mapa. O grafo e o índice de pesquisa distribuídos são bases de dados derivadas ODbL, e isso tem de ficar indicado.
- **Rede Ciclável © Câmara Municipal de Lisboa**, CC BY 4.0, com a indicação de que os dados foram modificados (normalizados e conflacionados). **Não se importam dados da CML para o OSM** sem autorização explícita.
- **Protomaps** (dados OSM) e fontes OFL. **Elevação**: Mapterhorn, com dados da Direção-Geral do Território (LiDAR, CC BY 4.0) e Copernicus GLO-30 (© DLR e.V. 2010-2014 e © Airbus Defence and Space GmbH 2014-2018, fornecido no âmbito do COPERNICUS pela União Europeia e a ESA). Fica registada em `graph.meta.json`.
- MapLibre (BSD-2) e restantes bibliotecas no ecrã de licenças. O GraphHopper (Apache 2.0) só corre no pipeline.
- **Sem segredos**: a app não usa chaves de API. Qualquer configuração do CI fica em GitHub Secrets.

## Riscos e mitigação

- **Correção do router próprio**: o parsing de OSM fica a cargo do GraphHopper, há testes de propriedades e rotas de referência no CI.
- **Desalinhamento entre a CML e o OSM**: relatório QA, `overrides.geojson` e atribuição só à aresta mais próxima.
- **Ruído na elevação** em pontes, viadutos e túneis: suavização, interpolação e validação em subidas conhecidas.
- **PMTiles locais ou Vulkan no emulador**: verificação logo na Fase 1, com o artefacto OpenGL e MBTiles como plano B.
- **Tamanho do pacote** (estimativa: 60 a 100 MB com o mapa base): região configurável e atualizações só dos ficheiros que mudaram.
