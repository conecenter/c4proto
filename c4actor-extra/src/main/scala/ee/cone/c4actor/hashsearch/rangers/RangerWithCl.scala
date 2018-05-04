package ee.cone.c4actor.hashsearch.rangers

import ee.cone.c4actor.Ranger

abstract class RangerWithCl[By <: Product, Field](val byCl: Class[By], val fieldCl: Class[Field]) extends Ranger[By, Field]
