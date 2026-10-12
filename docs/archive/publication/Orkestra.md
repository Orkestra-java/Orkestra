# Orkestra

Especificação de uma biblioteca pública Java para orquestração de tarefas dentro da aplicação.

Status: núcleo e adaptadores opcionais implementados em Java 25; 73 testes aprovados. Consulte [README.md](README.md) para a API executável e [validação](docs/VALIDATION.md) para evidências e limites. Os esboços abaixo ilustram o domínio; exemplos compiláveis estão em `examples/`.

Plataforma mínima: **Java 25**.

## Propósito

Decompor operações grandes em tarefas e grupos pequenos, tornando o processo fácil de escrever, ler, executar e observar. A biblioteca coordena sequência, decisões, paralelismo, acesso a recursos, resiliência e compensações. Cada execução pode ser identificada e seu percurso reconstruído, incluindo tempos e falhas.

A execução acontece dentro da aplicação Java. Persistir o histórico não implica execução durável, replay ou retomada após reinício. A Orkestra não exige servidor de workflows ou BPMN.

## Referências

- [Easy Flows](https://github.com/j-easy/easy-flows): referência de composição simples de tarefas Java.
- [Apache Camel Java DSL](https://camel.apache.org/manual/java-dsl.html): referência de composição fluente.
- [Resilience4j](https://resilience4j.readme.io/docs/getting-started): referência e candidato a provedor opcional de resiliência.
- [Temporal Java](https://docs.temporal.io/develop/java): comparação com execução durável apoiada por serviço.
- [Conductor OSS](https://docs.conductor-oss.org/devguide/concepts/index.html): comparação com coordenação distribuída e persistência do progresso.

O diferencial pretendido é a combinação de composição embarcada, grupos pequenos, contexto próprio da aplicação, concorrência com virtual threads e rastreamento hierárquico das execuções.

## Linguagem do domínio

| Conceito | Nome | Responsabilidade |
|---|---|---|
| Definição | `Composition` | Descreve tarefas, grupos, decisões e políticas |
| Coordenador | `Orchestrator` | Executa composições e controla seu ciclo de vida |
| Execução | `Execution` | Identifica uma ocorrência, seu resultado e histórico |
| Unidade de trabalho | `Task` | Encapsula uma operação com entrada e resultado definidos |
| Dados de execução | `ExecutionContext` | Conceito representado por um objeto próprio da aplicação |

`ExecutionContext` não obriga o usuário a herdar uma classe ou utilizar um mapa da biblioteca. Metadados internos de execução permanecem separados do contexto de negócio.

## Princípios de DDD e arquitetura

- A linguagem acima representa o domínio de orquestração; regras de pedidos, pagamentos ou outros negócios pertencem à aplicação consumidora.
- Entidades e serviços de negócio não precisam herdar classes da Orkestra.
- O modelo de definição expressa composição e políticas; a coordenação aplica esses contratos durante uma execução.
- Cache, locks distribuídos, histórico, logs, telemetria, contêiner de dependências e integrações de servidor são acessados por contratos de extensão.
- Implementações locais e adaptadores externos ficam separados do modelo central. O núcleo não depende de Spring, Grafana, Redis ou Resilience4j.
- Os módulos são `orkestra-core`, `orkestra-observability`, `orkestra-opentelemetry` e `orkestra-spring`. DDD não será reduzido a uma estrutura de pastas ou à criação artificial de agregados.

## Estilo da API

A composição principal conta a história do processo. Composições menores detalham partes dessa história; tarefas encapsulam operações. O padrão é compor grupos pequenos em grupos maiores.

Nomes explícitos são opcionais por meio de `named`. O nome de uma função ou classe deve comunicar sua intenção. Java não permite inferir de forma confiável o nome de toda lambda ou referência de método; quando não houver identidade descritiva disponível, a biblioteca gera uma identificação e permite personalizá-la. IDs internos não dependem de nomes únicos.

Políticas de um grupo são declaradas dentro de seu bloco. Políticas encadeadas após uma tarefa pertencem àquela tarefa. O prazo global pertence à configuração inicial da composição. A ordem textual das políticas não define implicitamente sua precedência.

### Vocabulário aprovado

| Operação | DSL |
|---|---|
| Executar tarefa ou composição | `perform` |
| Condição obrigatória | `require` |
| Decisão | `situation` |
| Seleção por valor | `basedOn`, `is`, `then`, `otherwise` |
| Seleção booleana | `when`, `then`, `otherwise` |
| Ramos paralelos | `parallel` |
| Reunião de resultados paralelos | `join` |
| Iteração | `forEach` |
| Grupo com compensação | `withCompensation` |
| Compensação de tarefa ou grupo | `compensateWith` |
| Cache | `cache`, `named`, `key`, `expiresAfter` |
| Prazo máximo | `within` |
| Novas tentativas | `retry`, `upTo`, `waiting` |
| Handler de erros | `onFailure` |
| Tradução de falha | `onFailure` recebe um handler que produz uma exceção |
| Limite de concorrência | `concurrently` |
| Exclusividade por chave | `exclusively` |
| Isolamento de capacidade | `isolate` |
| Circuit breaker | `guard` |
| Espera | `waitFor` |

`fetch`, `include`, `ensure` e `saga` não fazem parte do vocabulário escolhido. Todas as tarefas e composições são executadas com `perform`. `then` e `otherwise` recebem tarefas ou composições previamente definidas.

## Exemplos de leitura

Os exemplos são esboços da linguagem aprovada, não código compilável nem contratos definitivos de generics, builders ou criação de tarefas.

### Composição principal

```java
var placeOrder = Compositions.create()
    .named("place-order")
    .within(Duration.ofSeconds(30))
    .perform(validateOrder)
    .perform(prepareOrder)
    .withCompensation(group -> group
        .perform(reserveInventory)
        .perform(collectPayment)
        .perform(arrangeDelivery)
        .perform(confirmOrder)
            .compensateWith(cancelOrder)
    )
    .perform(notifyCustomer)
    .build();

// O núcleo sequencial implementa perform(composition, context, input).
// placeOrder transforma o input inicial no output final.
```

### Paralelismo e reunião

```java
var prepareOrder = Compositions.create()
    .parallel(group -> group
        .concurrently(3)
        .within(Duration.ofSeconds(5))
        .perform(loadCustomer)
        .perform(loadStock)
        .perform(loadShippingOptions)
    )
    .join(prepareOrderData)
    .perform(calculateTotal)
    .build();
```

`join` acessa os resultados por referências tipadas das tarefas, sem exigir casts de um mapa. O primeiro protótipo representa esse acesso em `Results.get`. Nomes de observabilidade não são chaves de acesso aos resultados.

### Decisões

```java
.situation(choice -> choice
    .basedOn(paymentMethod)
    .is(CREDIT_CARD).then(collectCardPayment)
    .is(PIX).then(collectPixPayment)
    .otherwise(rejectPayment)
)

.situation(choice -> choice
    .when(customerIsPremium).then(applyPremiumBenefits)
    .otherwise(applyStandardBenefits)
)
```

### Iteração

```java
.forEach(orderItems, item -> item
    .concurrently(8)
    .within(Duration.ofSeconds(10))
    .require(stockIsAvailable)
    .perform(reserveStock)
        .compensateWith(releaseStock)
        .exclusively(productId)
)
```

`forEach` extrai uma coleção do input atual. Cada item é o input inicial de uma iteração; dentro do bloco, cada `perform` recebe o output anterior. O output final do grupo é uma lista dos outputs da última operação de cada iteração, preservando a ordem dos itens de entrada mesmo com concorrência. Essa lista é o input do próximo passo. O contexto permanece compartilhado; o item não é injetado em um campo mutável comum. A API executável recebe um extrator `Iterable<Item>` e uma função que configura o builder tipado da iteração.

### Políticas de tarefa

```java
.perform(loadCustomer)
    .cache(cache -> cache
        .named("customers")
        .key(customerId)
        .expiresAfter(Duration.ofMinutes(5))
    )
    .isolate("customer-service")
    .concurrently(20)
    .guard("customer-service")
    .retry(retry -> retry
        .upTo(3)
        .waiting(Duration.ofMillis(200))
    )
    .within(Duration.ofSeconds(2))
    .onFailure(customerErrors::translate)
```

As políticas também podem ser encapsuladas em definições reutilizáveis de tarefas para manter as composições pequenas. `TaskRef.of(task)` cria uma participação reutilizável com políticas imutáveis.

## Contrato de execução e dados

Cada tarefa possui `Input`, contexto próprio da aplicação e `Output`. O output de um `perform` é o input do próximo. Uma composição também possui input e output tipados e pode ser usada como uma tarefa. O output final é produzido pela última operação, sem exigir `returning`. O estado interno do coordenador, os resultados e o histórico não são armazenados automaticamente no contexto.

Tarefas paralelas recebem o mesmo input e compartilham o contexto, produzindo outputs independentes. `parallel` aguarda os ramos. `join` é obrigatório após o grupo: combina os outputs e produz o input do próximo passo. Só executa quando todos concluírem com sucesso, sem recuperação por `onFailure`. Essa decisão substitui a proposta anterior de join opcional.

A biblioteca não torna objetos de negócio mutáveis automaticamente seguros. O padrão é leitura compartilhada e combinação explícita dos resultados. Alterações concorrentes no contexto são responsabilidade da aplicação.

Uma falha definitiva interrompe a composição inteira: nenhum novo passo é iniciado e os ramos ativos recebem solicitação de cancelamento. `onFailure` não recupera a execução nem produz output de sucesso; traduz a falha em uma exceção apropriada para a aplicação. O executor lança uma exceção em caso de falha. Retry continua sendo uma política anterior à falha definitiva.

### Requisitos e outputs

`require` valida contexto e input atual. Em caso de sucesso, repassa o mesmo input sem transformação; em caso de rejeição, falha e encerra o fluxo. A API recebe `Condition<C,I>` e, opcionalmente, um `Supplier<? extends Exception>`; o padrão é `RequirementException`.

Todo `perform` produz output explícito. Não há sobrecarga de ação sem retorno que preserve automaticamente o input.

### Tipagem de decisões

Todos os caminhos de `situation` recebem o mesmo input atual e produzem outputs de um tipo comum. O output do caminho escolhido é o input do próximo passo. `then` e `otherwise` aceitam tarefas ou composições. A escolha do caminho não altera o contrato de tipos do encadeamento.

## Concorrência e acesso a recursos

- Ramos de `parallel` e `forEach` usam virtual threads.
- `concurrently(n)` em um grupo limita ramos simultâneos daquela execução.
- Em uma tarefa, o limite vale entre execuções da mesma definição no mesmo `Orchestrator`.
- `isolate(name)` cria uma fronteira de capacidade independente. Tarefas no mesmo grupo compartilham a capacidade configurada.
- Ao atingir o limite, a operação aguarda uma vaga com um mecanismo de semáforo, respeitando prazo e cancelamento. A espera conta no `within`.
- `exclusively(key)` impede acesso simultâneo à mesma chave. O padrão é local ao coordenador; provedores distribuídos são extensões.
- Recursos de capacidade e exclusividade precisam ser liberados mesmo após falha, timeout ou cancelamento.

Virtual threads não eliminam limites de conexões, memória ou capacidade de serviços externos. Limites diferentes para o mesmo isolamento são rejeitados. A aquisição de capacidade/isolamento ou chave exclusiva mantida por um ancestral é rejeitada antes da espera, inclusive em cache miss e ramos paralelos. A revisão em `docs/VALIDATION.md` registra essa limitação.

## Cache

- Armazena somente resultados de sucesso.
- Sem `named`, usa cache local em memória, compartilhado entre requests e separado por tarefa no escopo da aplicação/coordenador.
- Com `named`, seleciona um cache configurado, permitindo provedores externos.
- `key` calcula a identidade do recurso a partir do contexto; `expiresAfter` determina o TTL. O valor armazenado é o output da tarefa. O usuário deve garantir que a chave represente todas as diferenças relevantes para esse output.
- Pedidos simultâneos da mesma tarefa e chave compartilham uma única execução de carregamento.
- Cancelar um interessado não cancela o carregamento enquanto outros ainda aguardam.
- Uma execução cancelada não continua seus próximos passos por causa de um resultado que chegue posteriormente.

Resultados compartilhados devem ter um contrato de imutabilidade ou cópia definido pela aplicação/provedor. O cache local padrão limita 1024 entradas, oferece invalidação e TTL; o último interessado solicita cancelamento do carregamento.

## Prazos, falhas e recuperação

`within` limita o tempo total do elemento: espera por capacidade, tentativas e intervalos de retry. O prazo efetivo respeita também os prazos dos grupos e da composição que o contém.

Timeout encerra a composição, impede novos passos e solicita interrupção dos trabalhos ativos. O handler pode observar e tratar o erro, mas não recuperar para continuar. O mesmo vale para cancelamento e encerramento forçado.

Java permite solicitar interrupção, mas não garante encerrar código que a ignora. Operações externas precisam de seus próprios timeouts. Trabalho que não terminou deve ser reportado como pendente; a biblioteca não promete matar threads à força.

`onFailure` existe em tarefas, grupos e na composição inteira. O handler mais próximo é consultado primeiro. Recebe contexto e exceção/causa e devolve uma exceção apropriada, preservando a causa original. O handler mais próximo traduz uma vez, após cleanup; handlers externos não traduzem novamente. Falha ou retorno null do handler preserva a causa original.

O executor lança uma exceção em caso de falha. A aplicação captura essa exceção e a converte em sua resposta; o núcleo não determina status HTTP ou formato de apresentação. A falha e sua tradução devem ser correlacionáveis com a execução nos eventos e no histórico configurado.

O handler não fornece um output de sucesso e não retoma o fluxo, inclusive após timeout, cancelamento ou encerramento forçado. A tradução mantém os diagnósticos de compensação e trabalho pendente. Essa decisão substitui a proposta anterior de `propagate` / `recoverWith` e de retorno de erro estruturado.

## Retry e circuit breaker

`retry(...upTo(3))` significa três tentativas totais, incluindo a primeira. `waiting` expressa o intervalo entre tentativas. Timeout e cancelamento encerram o retry. Falhas de negócio só são repetidas por uma regra explícita.

`guard(name)` compartilha o circuit breaker por nome dentro do `Orchestrator`. Circuito aberto rejeita imediatamente a chamada; após o intervalo configurado, permite chamadas de teste.

As políticas pertencem à API da Orkestra, com integração opcional com Resilience4j. O provedor não deve exigir mudanças na composição do consumidor. O breaker local usa falhas consecutivas e uma tentativa half-open. Retry usa intervalo fixo e regra explícita; precedência e configuração estão descritas nos contratos de implementação abaixo. Um adaptador Resilience4j não faz parte dos módulos atuais.

## Compensação

`withCompensation` delimita ações cujos efeitos devem ser compensados após uma falha não recuperada. Abrange grupos internos e suas ações concluídas.

- Cada compensação recebe contexto, input original e output da tarefa concluída. Referências de métodos e objetos de compensação podem ser passados diretamente a `compensateWith`, sem lambda adaptadora quando as assinaturas forem compatíveis.
- A compensação explícita de um grupo recebe contexto, input inicial e output final do grupo. As compensações internas recebem os argumentos das respectivas tarefas.
- Compensa somente ações concluídas com sucesso e registradas para compensação.
- Respeita a ordem inversa das dependências. Não há uma ordem de conclusão garantida entre ações paralelas independentes.
- Uma falha de compensação não impede a tentativa das demais; todos os erros são reunidos no resultado.
- Há um prazo próprio para cancelamento e compensação, separado do prazo da operação normal.
- Antes de compensar efeitos dependentes de uma tarefa cancelada, aguarda-se seu encerramento dentro do prazo. Se ela continuar alterando recursos, registra-se a pendência; não se presume uma compensação segura.
- O handler de um bloco com compensação é chamado após o cancelamento e a fase de compensação, incluindo registro de pendências.

Um grupo pode expor suas compensações internas ou declarar `compensateWith` próprio. Se tiver concluído com sucesso, sua compensação explícita substitui as internas para evitar dupla compensação. Se falhar durante sua execução, as compensações internas cuidam das ações concluídas.

Compensação não é rollback atômico. A aplicação define ações adequadas aos efeitos de negócio. Falhas parciais durante uma tarefa e efeitos repetidos por retry exigem contratos próprios de idempotência e recuperação.

## Graceful shutdown

O `Orchestrator` deixa de aceitar novas execuções e aguarda as atuais até um prazo configurado. Ao esgotar o prazo, solicita cancelamento e conduz as compensações aplicáveis com seu orçamento próprio. Reporta trabalhos e compensações pendentes.

A integração com o servidor interrompe a entrada de requests. O núcleo controla as execuções da biblioteca, não o servidor HTTP inteiro. A integração opcional com Spring deve coordenar esses ciclos de vida.

## Observabilidade e histórico

Cada execução possui ID próprio e pode receber o ID de correlação da request. Eventos estruturados representam:

- Início, término, duração, status e causa de falha de tarefas e grupos.
- Hierarquia entre composição, grupos e tarefas.
- Caminhos de decisão e resultados da reunião de ramos.
- Tentativas, uso de cache, espera por capacidade e compensações.
- Cancelamentos, timeouts e trabalhos pendentes.

O núcleo emite eventos por contratos de extensão. Adaptadores opcionais integram logs estruturados e OpenTelemetry ao ecossistema Grafana.

Exportação assíncrona usa fila limitada. Por padrão, fila cheia descarta eventos e contabiliza perdas, preservando a execução da aplicação. Uma política mais rigorosa de histórico pode ser configurada; histórico incompleto deve ser identificável.

Um provedor opcional guarda o histórico e permite consulta por ID para um futuro frontend. A visualização pode mostrar a árvore e o percurso de uma execução, tempos e falhas. Compatibilidade BPMN e frontend não são requisitos do núcleo inicial.

O contexto não é registrado automaticamente. A aplicação seleciona atributos exportados. Medição e emissão devem ter custo controlado; metas de desempenho serão definidas e medidas, não presumidas.

## Escopo aprovado e sequência de trabalho

A visão inclui composição, decisões, requisitos, paralelismo, join, iteração, políticas de prazo e concorrência, cache, retry, circuit breaker, handlers, compensação e graceful shutdown, além dos contratos de observabilidade e extensão.

Implementação deve começar com o núcleo e contratos de dados/ciclo de vida, seguindo para concorrência e compensação, políticas e adaptadores. Essa sequência não reduz o escopo aprovado. Frontend, execução durável e infraestrutura distribuída própria ficam fora do núcleo inicial.

Os contratos técnicos foram definidos e implementados nos módulos Maven abaixo. Mudanças futuras de API devem ser discutidas com o autor antes de alterar os contratos. Publicação remota depende de um processo de release separado e de verificação de titularidade das coordenadas.

## Referência anterior

[Orkestra.reference.md](Orkestra.reference.md) preserva integralmente o documento consolidado anterior. Seu código, nomes, dependências e alegações de funcionalidades são material de referência, não a especificação vigente nem evidência de implementação desta versão.

## Primeiro protótipo de assinaturas

[design/api/README.md](design/api/README.md) apresenta contratos isolados para tarefas, referências por participação, grupos paralelos e `join`. O consumidor acessa resultados tipados por referência, conforme aprovado. A proposta não implementa execução e modela `Task<C, I, O>` e `Composition<C, I, O>`. O output anterior define o input seguinte. `parallel` aguarda todos os ramos; `join` é obrigatório e produz a próxima entrada. O protótipo é histórico; a implementação atual inclui os demais operadores tipados.

O protótipo isolado foi compilado com sucesso usando `javac --release 25` em 2026-10-12. Essa verificação não implementa os operadores propostos.


## Resultado público da execução

Em caso de sucesso, `Orchestrator.perform(composition, context, input)` retorna `Execution<O>`, com output final, ID, instantes de início e término e duração. O consumidor acessa o resultado com `execution.output()` e correlaciona o percurso com `execution.id()`. A duração é medida por relógio monotônico; os instantes servem à correlação temporal.

Em caso de falha, o executor lança uma exceção. Se houver tradução por `onFailure`, preserva o tipo de exceção escolhido pelo handler, sem envolver indiscriminadamente toda exceção em um wrapper genérico. Na ausência de tradução, aplica a política padrão de propagação, cuja assinatura pública inicial declara `throws Exception`.

`Failures.details(error)` extrai o ID e diagnósticos de timeout, cancelamento, compensação e trabalho pendente, sem registro global. Uma exceção nova com supressão habilitada recebe metadados mantendo seu tipo. Supressão desabilitada ou reutilização da exceção exige `ExecutionFailureException`, preservando a causa e distinguindo as ocorrências. O ID independe de um provedor de histórico.

Output de sucesso e falha não se misturam: não há retorno `Execution` com status de falha no lugar da exceção. Histórico e eventos continuam descrevendo a execução com erro, conforme a configuração de observabilidade.

### Exemplo de consumo

```java
try {
    var execution = orchestrator.perform(placeOrder, context, request);
    return applicationResponse(execution.output(), execution.id());
} catch (Exception error) {
    var details = failures.details(error);
    return applicationErrorResponse(error, details);
}
```

As funções de resposta pertencem à aplicação. O núcleo não conhece HTTP, controllers ou formatos de resposta. `Execution`, `FailureDetails`, `FailureHandler`, `Orchestrator` e `Failures` estão implementados no núcleo. O protótipo histórico continua separado da API executável.

## Primeiro marco de implementação — histórico

O primeiro marco implementou o encadeamento sequencial e handlers de composição, com 22 testes aprovados. Foi ampliado pelos contratos abaixo; suas limitações antigas de diagnóstico e ausência de políticas não descrevem a versão atual. O protótipo permanece arquivado em `design/api/`.

## Contratos de implementação completa — 2026-10-12

Licença aprovada: Apache-2.0. Coordenadas aprovadas: `io.orkestra`, com
`orkestra-core` como núcleo JPMS. Publicação remota continua sendo uma ação
separada. Maven Wrapper 3.3.4 fixa Maven 3.9.16; CI executa Java 25 e verify.

### Definições e políticas

Definições e políticas são imutáveis. `TaskRef<C,I,O>` representa uma
participação e também permite configurar tarefas reutilizáveis. O builder
retornado por `perform` conserva os tipos de input e output da participação,
inclusive para `compensateWith(context, inputOriginal, output)`.
Políticas encadeadas depois de `perform` se aplicam à última participação;
antes do primeiro passo, `within` configura a composição. Uma definição
construída pode receber `onFailure` e `within` de composição explicitamente.

`parallel` usa um builder de grupo e exige `join`, cujo `Results.get` só aceita
referências daquele grupo. Referências duplicadas são rejeitadas ao definir o
grupo. `situation` seleciona por predicado (`when`) ou valor (`basedOn/is`) e
exige `otherwise`; caminhos compartilham input e tipo de output.
`forEach` extrai uma coleção e recebe uma definição tipada por item; devolve
uma lista na ordem original. `require` aceita predicado de contexto/input e
uma fábrica opcional da exceção de rejeição. `waitFor` preserva input e
respeita cancelamento/prazo.

### Execução, cancelamento e compensação

O coordenador controla virtual threads. A chamada `perform` aguarda o
resultado; `within` inclui espera, execução e retries. Cancelamento impede
novos passos e solicita interrupção. A conclusão real de trabalho cancelado
é observada separadamente do estado de Future. O orçamento de cancelamento
é separado do orçamento de compensação. Se trabalho continuar ativo, as
compensações são adiadas/omitidas conservadoramente e a pendência é reportada;
a biblioteca nunca presume segurança para desfazer efeitos ainda mutáveis.

`withCompensation` delimita registros de ações bem-sucedidas. Após falha,
registros são executados em ordem inversa de conclusão, que respeita as
dependências sequenciais e permite qualquer ordem entre ramos independentes.
Compensação explícita de grupo bem-sucedido substitui registros internos.
Erros de compensação são reunidos e não impedem as demais tentativas dentro
do orçamento. O handler mais próximo é aplicado uma vez, após cleanup e
compensação. Falha do handler preserva a causa original. Interrupção do
chamador é restaurada depois do cleanup.

Shutdown rejeita entradas, aguarda o prazo de graça, solicita cancelamento e
aguarda cleanup limitado. O relatório lista execuções e trabalhos pendentes.
`close` usa os orçamentos configurados e não aguarda indefinidamente threads
que ignoram interrupção.

### Ordem e estado das políticas

A ordem é fixa: cache/single-flight, deadline, capacidade, exclusividade,
retry e permissão do breaker por tentativa. Capacidade e exclusividade são
mantidas durante retries e liberadas no encerramento real da tarefa, inclusive
quando o chamador já recebeu timeout. Limites isolados de mesmo nome devem
usar a mesma capacidade; conflitos são rejeitados.

Retry conta tentativas totais; a repetição requer `retryIf` explícito.
Timeout, cancelamento, rejeição de breaker e erros fatais não são repetidos.
Breaker local conta falhas consecutivas, abre pelo intervalo configurado e
permite uma tentativa half-open. O estado é por nome e coordenador.

Cache local tem capacidade padrão 1024, TTL monotônico, invalidação por chave
e invalidação total. Cache nomeado deve estar registrado. Sucessos, inclusive
null, podem ser armazenados; falhas não. Interessados compartilham carregamento;
a saída de um interessado não cancela enquanto houver outro. Sem interessados,
o carregamento recebe interrupção e seu encerramento real continua sendo
observado. Cache e compensação explícita na mesma tarefa são rejeitados para
evitar desfazer um efeito compartilhado duas vezes. Chaves devem representar
todas as diferenças do resultado; outputs compartilhados devem ser seguros
para a aplicação.

### Diagnóstico e extensões

Se a exceção for nova e aceitar supressão, o tipo original/traduzido é preservado
e recebe um diagnóstico. Caso contrário, incluindo exceções reutilizadas ou
compartilhadas, `ExecutionFailureException` preserva a causa
original e disponibiliza o ID sem mapa global. Isso fecha a limitação do
primeiro marco. Diagnósticos incluem erros de compensação e trabalho pendente.

Eventos incluem IDs de execução/correlação, span e parent span, nome, tipo,
tempo, duração, falha e atributos explicitamente selecionados. Contexto e
outputs não são exportados automaticamente. Contratos SPI permitem cache,
locks, breaker e eventos/histórico sem dependências externas. Histórico local
é limitado por execuções/eventos e TTL, com consulta e remoção. Exportação
assíncrona usa fila limitada, conta descartes/falhas e marca histórico incompleto;
modo estrito pode rejeitar exportação. Adaptadores externos são opcionais e
separados do núcleo. Não há implementação própria de infraestrutura distribuída.

Testes de contrato devem cobrir tipagem negativa, concorrência real, falha de
join, cancelamento não cooperativo, prazos aninhados, cleanup de recursos,
cache compartilhado, limites/TTL, breaker, retries, compensações, shutdown,
eventos e fila saturada. Funcionalidades só serão marcadas verificadas após
build, execução de testes e exemplos.

### Adaptadores opcionais e limites de publicação

`orkestra-observability` oferece logs JSON com `java.util.logging`.
`orkestra-opentelemetry` traduz os eventos em spans usando um `Tracer` fornecido
pela aplicação, sem instalar SDK ou exporter global. `orkestra-spring` oferece
`SmartLifecycle` para um coordenador fornecido pela aplicação; o phase é
configurável e deve parar a entrada de requests antes do coordenador.
São módulos opcionais: `orkestra-core` não depende deles. Grafana recebe os
spans pelo exporter configurado na aplicação, sem cliente próprio no núcleo.
Os provedores locais de cache, locks e breaker funcionam sem Redis ou Resilience4j;
os SPIs aceitam implementações externas sem alterar a DSL.

Timeout pertence ao elemento que configurou o prazo efetivo: seu handler, ou
o handler ancestral mais próximo, traduz a falha. Um handler de tarefa não
substitui o handler de um prazo global da composição. Cancelamento decorrente
desse timeout mantém a classificação TIMEOUT, sem depender de qual thread
observou a expiração primeiro.

O relatório de shutdown inclui IDs de execuções com trabalho destacado que
sobreviveu ao resultado síncrono e IDs de compensações ativas/omitidas por
trabalho ainda pendente. O relatório de conclusão não garante rollback de
negócio; falhas e compensações omitidas permanecem nos diagnósticos/histórico.

Exceções reutilizadas ou compartilhadas entre execuções não recebem múltiplos
IDs sobrepostos. A primeira ocorrência pode preservar a instância; as demais
usam `ExecutionFailureException` com a causa original e seu próprio diagnóstico.
A mesma exceção impede associar um novo ID de forma inequívoca sem wrapper.
O estado interno de propagação de falhas de um carregamento compartilhado é
copiado por interessado, para não compartilhar estado mutável de handlers.

Retry e cache são políticas de tarefas folha: a definição rejeita aplicá-los
à composição inteira, a decisões ou a requisitos. Isso evita repetir grupos
já cancelados e armazenar efeitos internos compensáveis. Grupos podem usar
prazos, limites, handlers e compensação, e suas tarefas podem usar retry/cache.

## Melhorias de segurança, medição e release — 2026-10-12

A execução rejeita com `IllegalArgumentException` uma participação que tenta
readquirir capacidade/isolamento ou chave exclusiva mantida por um ancestral.
A rejeição ocorre antes de esperar pelo recurso, inclusive no cache miss e
em ramos paralelos. Recursos não se tornam reentrantes. A chave exclusiva é
avaliada uma vez por participação; um cache hit continua dispensando aquisição.
Escopos independentes continuam compartilhando capacidade e locks normalmente.
Essa verificação cobre a árvore da execução; chamadas recursivas manuais a
`Orchestrator.perform` e ciclos entre execuções independentes não são inferidos.

CI terá uma execução com diretórios vazios para distribuição e dependências
Maven, sem restauração de cache. Falha de rede local deve ser reportada como
impedimento da validação de downloads, sem substituí-la por um build offline.

Benchmarks executáveis medirão sequência, paralelismo, cache e concorrência,
com aquecimento, processos separados e registro do ambiente. Números locais
são uma linha de base, não promessa de desempenho. O harness JMH opcional
mede throughput, latência e alocação via profiler GC quando disponível.

O perfil Maven `release` anexará fontes e Javadoc aos quatro módulos. Um
snapshot versionado das assinaturas públicas e verificação de compatibilidade
impedirão remoções ou mudanças binárias não revisadas. Fontes e documentação
podem ser geradas localmente pelo JDK quando plugins não estiverem disponíveis;
isso não comprova o perfil Maven remoto. Nenhum comando desta etapa publica
artefatos ou fixa dados de titularidade não verificados.
