package ee.cone.c4actor.hashsearch.condition

import ee.cone.c4actor.ConditionCheck

abstract class ConditionCheckWithCl[By <: Product, Field](val byCl: Class[By],val fieldCl: Class[Field]) extends ConditionCheck[By, Field]
