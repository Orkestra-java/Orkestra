# Protótipo histórico de contratos Java

Proposta histórica isolada, sem engine ou execução de tarefas. A API vigente está em `orkestra-core/`; consulte o README da raiz e os exemplos executáveis. As decisões em aberto abaixo descrevem o estado deste protótipo, não pendências da implementação atual.

- `Task<C, I, O>` recebe contexto e input e devolve output tipado.
- `Composition<C, I, O>` preserva o input inicial e o output final do encadeamento.
- `Builder<C, I, Current>` mantém o tipo atual: `perform` exige esse input e muda o tipo atual para o output da tarefa ou composição.
- `TaskRef<C, I, O>` identifica uma participação. Duas referências da mesma tarefa representam participações diferentes.
- Todos os ramos de `parallel` recebem a mesma entrada e o contexto compartilhado, produzindo outputs independentes.
- `parallel` aguarda os ramos. `join` é obrigatório para combinar outputs e definir a próxima entrada.
- `AfterParallel` só expõe `join`: não permite continuar ou construir sem a combinação.
- `Results.get(reference)` preserva o tipo do output; a implementação poderá precisar de cast interno validado, sem expô-lo ao consumidor.
- A fábrica foi expressa como `Compositions` para manter apenas contratos, sem uma implementação fictícia de `Composition.create()`.

Na implementação, cada referência deve ser registrada uma única vez no grupo, e `Results` só deve resolver referências do grupo correspondente. Nome não define identidade. Acesso indevido deve produzir erro descritivo. Repetições em `forEach` precisarão de uma identidade adicional por item.

`OrderExample.java` demonstra o encadeamento `OrderRequest → ValidatedOrder → PreparedOrder → ConfirmedOrder`. A preparação usa três ramos com a mesma entrada e um join que produz `PreparedOrder`. Não existe operação `returning`: o output final é o resultado da última operação.

Comportamentos adicionais aprovados: `situation` recebe o input atual e seus caminhos produzem um tipo comum; `forEach` usa cada item como input de uma iteração e produz uma lista ordenada dos outputs finais. Esses operadores ainda não aparecem nas interfaces do protótipo.

Decisões em aberto: assinaturas Java de situações e iterações; tipagem de compensações; resultados de ramos compostos; operações sem resultado útil.

Verificação local em 2026-10-12: compilação concluída com sucesso usando JDK 25:

```sh
javac --release 25 -d /tmp/orkestra-api-check design/api/OrkestraApi.java design/api/OrderExample.java
```

## Ajustes de contrato posteriores

O executor deverá lançar uma exceção em caso de falha. `onFailure` traduz a causa em uma exceção apropriada para a aplicação, sem recuperação do encadeamento. Todo `perform` produz output explícito. Compensações recebem contexto, input original e output, podendo ser passadas por referência de método. A chave de cache é calculada pelo contexto. Essas operações ainda não foram adicionadas às interfaces deste protótipo.

## Retorno do executor

`Orchestrator.perform(composition, context, input)` retorna `Execution<O>` somente no sucesso. Em falha, lança uma exceção, preservando o tipo traduzido por `onFailure`. `Failures.details(error)` expõe um contrato de extração do ID e dos diagnósticos associados à exceção; seu mecanismo interno ainda não está implementado. A assinatura `throws Exception` torna explícitas exceções verificadas de tarefas e handlers nesta proposta.

Os novos tipos são contratos e registros de dados. Não implementam execução, observabilidade ou associação de diagnóstico. A compilação foi validada; isso não transforma as interfaces propostas em um executor.

## Implementação atual

O núcleo em `orkestra-core/` implementa sequência, paralelismo/join, decisões,
iteração, políticas, compensação, diagnóstico e shutdown. Os três módulos
opcionais fornecem logs, OpenTelemetry e ciclo de vida Spring. Consulte o
`README.md` da raiz para build, testes e exemplos executáveis. Este protótipo
não acompanha todas as assinaturas finais.
