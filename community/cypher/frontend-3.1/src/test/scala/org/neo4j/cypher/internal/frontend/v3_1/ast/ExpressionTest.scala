/*
 * Copyright (c) 2002-2016 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.cypher.internal.frontend.v3_1.ast

import org.neo4j.cypher.internal.frontend.v3_1.symbols._
import org.neo4j.cypher.internal.frontend.v3_1.test_helpers.CypherFunSuite
import org.neo4j.cypher.internal.frontend.v3_1.{DummyPosition, IdentityMap, SemanticDirection, SemanticState}

class ExpressionTest extends CypherFunSuite with AstConstructionTestSupport {

  val expression = DummyExpression(CTAny, DummyPosition(0))

  test("shouldReturnCalculatedType") {
    expression.types(SemanticState.clean) should equal(TypeSpec.all)
  }

  test("shouldReturnTypeSetOfAllIfTypesRequestedButNotEvaluated") {
    expression.types(SemanticState.clean) should equal(TypeSpec.all)
  }

  test("shouldReturnSpecifiedAndConstrainedTypes") {
    val state = (
      expression.specifyType(CTNode | CTInteger) chain
      expression.expectType(CTNumber.covariant)
    )(SemanticState.clean).state

    expression.types(state) should equal(CTInteger.invariant)
  }

  test("shouldRaiseTypeErrorWhenMismatchBetweenSpecifiedTypeAndExpectedType") {
    val result = (
      expression.specifyType(CTNode | CTInteger) chain
      expression.expectType(CTString.covariant)
    )(SemanticState.clean)

    result.errors should have size 1
    result.errors.head.position should equal(expression.position)
    expression.types(result.state) shouldBe empty
    result.errors.head.msg should equal ("Type mismatch: expected String but was Integer or Node")
  }

  test("shouldRaiseTypeErrorWithCustomMessageWhenMismatchBetweenSpecifiedTypeAndExpectedType") {
    val result = (
      expression.specifyType(CTNode | CTInteger) chain
      expression.expectType(CTString.covariant, (expected: String, existing: String) => s"lhs was $expected yet rhs was $existing")
    )(SemanticState.clean)

    result.errors should have size 1
    result.errors.head.position should equal(expression.position)
    expression.types(result.state) shouldBe empty

    assert(result.errors.size === 1)
    assert(result.errors.head.position === expression.position)
    assert(result.errors.head.msg == "Type mismatch: lhs was String yet rhs was Integer or Node")
    assert(expression.types(result.state).isEmpty)
  }

  test("should compute dependencies of simple expressions") {
    varFor("a").dependencies should equal(Set(varFor("a")))
    SignedDecimalIntegerLiteral("1")(pos).dependencies should equal(Set())
  }

  test("should compute dependencies of composite expressions") {
    Add(varFor("a"), Subtract(SignedDecimalIntegerLiteral("1")(pos), varFor("b"))_)(pos).dependencies should equal(Set(varFor("a"), varFor("b")))
  }

  test("should compute dependencies for filtering expressions") {
    // extract(x IN (n)-->(k) | head(nodes(x)) )
    val pat: RelationshipsPattern = RelationshipsPattern(
      RelationshipChain(
        NodePattern(Some(varFor("n")), Seq.empty, None)_,
        RelationshipPattern(None, optional = false, Seq.empty, None, None, SemanticDirection.OUTGOING)_,
        NodePattern(Some(varFor("k")), Seq.empty, None)_
      )_
    )_
    val expr: Expression = ExtractExpression(
      varFor("x"),
      PatternExpression(pat),
      None,
      Some(FunctionInvocation(FunctionName("head")_, FunctionInvocation(FunctionName("nodes")_, varFor("x"))_)_)
    )_

    expr.dependencies should equal(Set(varFor("n"), varFor("k")))
  }

  test("should compute dependencies for nested filtering expressions") {
    // extract(x IN (n)-->(k) | extract(y IN [1,2,3] | y) )
    val pat: RelationshipsPattern = RelationshipsPattern(
      RelationshipChain(
        NodePattern(Some(varFor("n")), Seq.empty, None)_,
        RelationshipPattern(None, optional = false, Seq.empty, None, None, SemanticDirection.OUTGOING)_,
        NodePattern(Some(varFor("k")), Seq.empty, None)_
      )_
    )_
    val innerExpr: Expression = ExtractExpression(
      varFor("y"),
      Collection(Seq(literalInt(1), literalInt(2), literalInt(3)))_,
      None,
      Some(varFor("y"))
    )_
    val expr: Expression = ExtractExpression(
      varFor("x"),
      PatternExpression(pat),
      None,
      Some(innerExpr)
    )_

    expr.dependencies should equal(Set(varFor("n"), varFor("k")))
  }

  test("should compute dependencies for pattern comprehensions") {
    // [ (n)-->(k) | k ]
    val pat: RelationshipsPattern = RelationshipsPattern(
      RelationshipChain(
        NodePattern(Some(varFor("n")), Seq.empty, None)_,
        RelationshipPattern(None, optional = false, Seq.empty, None, None, SemanticDirection.OUTGOING)_,
        NodePattern(Some(varFor("k")), Seq.empty, None)_
      )_
    )_
    val expr: Expression = PatternComprehension(
      namedPath = None,
      pattern = pat,
      predicate = None,
      projection = varFor("k")
    )_

    expr.dependencies should equal(Set(varFor("n"), varFor("k")))
  }


  test("should compute inputs of composite expressions") {
    val identA = varFor("a")
    val identB = varFor("b")
    val lit1 = SignedDecimalIntegerLiteral("1")(pos)
    val sub = Subtract(lit1, identB)(pos)
    val add = Add(identA, sub)(pos)

    IdentityMap(add.inputs: _*) should equal(IdentityMap(
      identA -> Set.empty,
      identB -> Set.empty,
      lit1 -> Set.empty,
      sub -> Set.empty,
      add -> Set.empty
    ))
  }

  test("should compute inputs for filtering expressions") {
    // given
    val pat = PatternExpression(RelationshipsPattern(
      RelationshipChain(
        NodePattern(Some(varFor("n")), Seq.empty, None)_,
        RelationshipPattern(None, optional = false, Seq.empty, None, None, SemanticDirection.OUTGOING)_,
        NodePattern(Some(varFor("k")), Seq.empty, None)_
      )_
    )_)

    val callNodes: Expression = FunctionInvocation(FunctionName("nodes") _, varFor("x"))_
    val callHead: Expression = FunctionInvocation(FunctionName("head") _, callNodes) _

    // extract(x IN (n)-->(k) | head(nodes(x)) )
    val expr: Expression = ExtractExpression(
      varFor("x"),
      pat,
      None,
      Some(callHead)
    )_

    // when
    val inputs = IdentityMap(expr.inputs: _*)

    // then
    inputs(callNodes) should equal(Set(varFor("x")))
    inputs(callHead) should equal(Set(varFor("x")))
    inputs(expr) should equal(Set.empty)
    inputs(pat) should equal(Set.empty)
  }
}
