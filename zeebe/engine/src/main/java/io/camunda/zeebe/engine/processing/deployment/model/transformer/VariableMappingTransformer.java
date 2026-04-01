/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Camunda License 1.0. You may not use this file
 * except in compliance with the Camunda License 1.0.
 */
package io.camunda.zeebe.engine.processing.deployment.model.transformer;

import io.camunda.zeebe.el.Expression;
import io.camunda.zeebe.el.ExpressionLanguage;
import io.camunda.zeebe.el.impl.NullExpression;
import io.camunda.zeebe.el.impl.StaticExpression;
import io.camunda.zeebe.model.bpmn.instance.zeebe.ZeebeMapping;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

/**
 * Transform variable mappings into an expression.
 *
 * <p>The resulting expression is a FEEL context that has a similar structure as a JSON document.
 * Each target of a mapping is a key in the context and the source of this mapping is the context
 * value. The source expression can be any FEEL expression. A nested target expression is
 * transformed into a nested context.
 *
 * <p>Variable mappings:
 *
 * <pre>
 *   source | target
 *   =======|=======
 *    x     | a
 *    y     | b.c
 *    z     | b.d
 * </pre>
 *
 * FEEL context expression:
 *
 * <pre>
 *   {
 *     a: x,
 *     b: {
 *       c: y,
 *       d: z
 *     }
 *   }
 * </pre>
 *
 * <p>Output variable mappings differ from input mappings that the result variables needs to be
 * merged with the existing variables if the variable is a JSON object. The merging is done by
 * calling the FEEL function 'context merge()' and referencing the variable.
 *
 * <pre>
 *   {
 *     a: x,
 *     b: if (b = null)
 *        then {
 *          c: y,
 *          d: z
 *        }
 *        else context merge(b, {
 *          c: y,
 *          d: z
 *       })
 *   }
 * </pre>
 */
public final class VariableMappingTransformer {

  private static final String EXPRESSION_MARKER = "=";

  public Expression transformInputMappings(
      final Collection<? extends ZeebeMapping> inputMappings,
      final ExpressionLanguage expressionLanguage) {

    final var mappings = toMappings(inputMappings, expressionLanguage);
    final var context = asContext(mappings);
    final var contextExpression =
        asFeelContextExpression(context, (contextValue, contextPath) -> contextValue);
    return parseExpression(contextExpression, expressionLanguage);
  }

  public Expression transformOutputMappings(
      final Collection<? extends ZeebeMapping> outputMappings,
      final ExpressionLanguage expressionLanguage) {

    final var mappings = toMappings(outputMappings, expressionLanguage);
    final var context = asContext(mappings);
    final var contextExpression = asFeelContextExpression(context, this::mergeContextExpression);
    return parseExpression(contextExpression, expressionLanguage);
  }

  /**
   * Transforms the output mappings into two separate expressions:
   *
   * <ol>
   *   <li>An expression based on parent scope.
   *       <ul>
   *         <li>Goal: Keep all parent scope variables intact when evaluating expression
   *       </ul>
   *   <li>An expression based on local scope.
   *       <ul>
   *         <li>Goal: Keep all local scope output mapping variables intact when evaluating
   *             expression
   *       </ul>
   * </ol>
   *
   * @return an {@link OutputMappingExpressions} containing both expressions
   */
  public OutputMappingExpressions transformOutputMappingsExpressions(
      final Collection<? extends ZeebeMapping> outputMappings,
      final ExpressionLanguage expressionLanguage) {

    final var mappings = toMappings(outputMappings, expressionLanguage);

    //    Captures existing variable values from the parent scope
    final var parentExpression = buildParentExpression(mappings, expressionLanguage);

    //    Builds the output-mapped values using context put()
    final var localOutputMappingExpression =
        buildLocalOutputMappingExpression(mappings, expressionLanguage);

    return new OutputMappingExpressions(parentExpression, localOutputMappingExpression);
  }

  private Expression buildParentExpression(
      final List<Mapping> mappings, final ExpressionLanguage expressionLanguage) {
    // Collect the top-level target keys (the first segment of each target path)
    final var topLevelKeys = new java.util.LinkedHashSet<String>();
    for (final Mapping mapping : mappings) {
      final var parts = splitPathExpression(mapping.target);
      topLevelKeys.add(parts.getFirst());
    }

    // Build a FEEL context expression that references each top-level key by name
    final var entries =
        topLevelKeys.stream().map(key -> key + ":" + key).collect(Collectors.joining(","));
    final var contextExpression = "{" + entries + "}";
    return parseExpression(contextExpression, expressionLanguage);
  }

  private Expression buildLocalOutputMappingExpression(
      final List<Mapping> mappings, final ExpressionLanguage expressionLanguage) {
    // This intentionally does NOT use MappingContextVisitor because context put() is a flat,
    // path-based function-call chain, whereas the visitor walks a grouped tree structure that
    // maps to context *literals* ({key: {nested: value}}).
    var expression = "{}";

    for (final Mapping mapping : mappings) {
      final var parts = splitPathExpression(mapping.target);
      final var sourceExpr = formatSourceExpression(mapping.source);

      if (parts.size() == 1) {
        expression =
            String.format("context put(%s,\"%s\",%s)", expression, parts.getFirst(), sourceExpr);
      } else {
        final var pathList =
            parts.stream().map(p -> "\"" + p + "\"").collect(Collectors.joining(","));
        expression = String.format("context put(%s,[%s],%s)", expression, pathList, sourceExpr);
      }
    }

    return parseExpression(expression, expressionLanguage);
  }

  private List<Mapping> toMappings(
      final Collection<? extends ZeebeMapping> mappings,
      final ExpressionLanguage expressionLanguage) {
    return mappings.stream()
        .map(
            mapping -> {
              final var source = mapping.getSource();
              final var sourceExpression =
                  source == null
                      ? new NullExpression()
                      : expressionLanguage.parseExpression(source);
              return new Mapping(sourceExpression, mapping.getTarget());
            })
        .collect(Collectors.toList());
  }

  private MappingContext asContext(final List<Mapping> mappings) {
    final var context = new MappingContext();

    for (final Mapping mapping : mappings) {
      final var sourceExpression = mapping.source;
      final var targetPathExpression = mapping.target;

      final var targetPathParts = splitPathExpression(targetPathExpression);
      createContextEntry(targetPathParts, sourceExpression, context);
    }
    return context;
  }

  private List<String> splitPathExpression(final String path) {
    final var parts = path.split("\\.");
    return new ArrayList<>(Arrays.asList(parts));
  }

  private void createContextEntry(
      final List<String> targetPathParts,
      final Expression sourceExpression,
      final MappingContext context) {
    final String target = targetPathParts.remove(0);

    if (targetPathParts.isEmpty()) {
      context.addEntry(target, sourceExpression);

    } else {
      final var nestedContext = context.getOrAddContext(target);
      createContextEntry(targetPathParts, sourceExpression, nestedContext);
    }
  }

  private String asFeelContextExpression(
      final MappingContext context,
      final BiFunction<String, List<String>, Object> contextValueVisitor) {
    return context.visit(feelContextBuilder(contextValueVisitor));
  }

  private MappingContextVisitor<String> feelContextBuilder(
      final BiFunction<String, List<String>, Object> contextValueVisitor) {
    return new MappingContextVisitor<>() {
      @Override
      public String onEntry(final String targetKey, final Expression sourceExpression) {
        return targetKey + ":" + formatSourceExpression(sourceExpression);
      }

      @Override
      public String onContext(final List<String> entries) {
        return "{" + String.join(",", entries) + "}";
      }

      @Override
      public String onContextEntry(
          final String targetKey, final String contextValue, final List<String> contextPath) {
        return targetKey + ":" + contextValueVisitor.apply(contextValue, contextPath);
      }
    };
  }

  private String mergeContextExpression(
      final String nestedContext, final List<String> contextPath) {
    // for a nested target mapping 'x -> a.b', append the nested property 'b' to
    // the existing context variable 'a' (instead of overriding 'a')
    // example: x = 1 and a = {'c':2} results in a = {'b':1, 'c':2}
    final var existingContext = String.join(".", contextPath);
    return String.format(
        "if (%s != null) then context merge(%s,%s) else %s",
        existingContext, existingContext, nestedContext, nestedContext);
  }

  private static String formatSourceExpression(final Expression sourceExpression) {
    if (sourceExpression instanceof StaticExpression) {
      // due to a regression (https://github.com/camunda/camunda/issues/16043) all the double
      // quotes inside the static expression must be escaped
      return String.format("\"%s\"", sourceExpression.getExpression().replaceAll("\"", "\\\\\""));
    }
    return sourceExpression.getExpression();
  }

  private Expression parseExpression(
      final String contextExpression, final ExpressionLanguage expressionLanguage) {
    final var expression =
        expressionLanguage.parseExpression(EXPRESSION_MARKER + contextExpression);

    if (!expression.isValid()) {
      throw new IllegalStateException(
          String.format(
              "Failed to build variable mapping expression: %s", expression.getFailureMessage()));
    }

    return expression;
  }

  private static final class MappingContext {

    private final Map<String, Object> entries = new LinkedHashMap<>();

    private final List<String> path;

    public MappingContext() {
      path = new ArrayList<>();
    }

    public MappingContext(final List<String> path) {
      this.path = path;
    }

    public void addEntry(final String key, final Expression value) {
      entries.put(key, value);
    }

    public MappingContext getOrAddContext(final String key) {
      final var entry = entries.get(key);

      if (entry instanceof MappingContext) {
        return (MappingContext) entry;

      } else {
        final var nestedPath = new ArrayList<>(path);
        nestedPath.add(key);
        final var nestedContext = new MappingContext(nestedPath);
        entries.put(key, nestedContext);

        return nestedContext;
      }
    }

    public <T> T visit(final MappingContextVisitor<T> visitor) {
      final var entries =
          this.entries.entrySet().stream()
              .map(
                  entry -> {
                    final var key = entry.getKey();
                    final var value = entry.getValue();

                    if (value instanceof final MappingContext nestedContext) {
                      final var contextValue = nestedContext.visit(visitor);

                      return visitor.onContextEntry(key, contextValue, nestedContext.path);
                    } else {
                      return visitor.onEntry(key, (Expression) value);
                    }
                  })
              .collect(Collectors.toList());

      return visitor.onContext(entries);
    }
  }

  private static final class Mapping {

    private final Expression source;
    private final String target;

    private Mapping(final Expression source, final String target) {
      this.source = source;
      this.target = target;
    }
  }

  private interface MappingContextVisitor<T> {
    T onEntry(String source, Expression target);

    T onContext(List<T> entries);

    T onContextEntry(final String target, final T contextValue, final List<String> contextPath);
  }

  public record OutputMappingExpressions(
      Expression parentExpression, Expression localOutputMappingsExpression) {}
}
