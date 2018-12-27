package ee.cone.dbrequest

import scalikejdbc.{NoExtractor, SQL, _}

trait DynamicDBAsk[R] {
  def resultHandler: WrappedResultSet ⇒ R

  def getListRO(sql: SQL[Nothing, NoExtractor]): List[R] =
    DB readOnly { implicit session ⇒
      sql.map(resultHandler).list().apply()
    }

  def getSingleRO(sql: SQL[Nothing, NoExtractor]): Option[R] =
    DB readOnly { implicit session ⇒
      sql.map(resultHandler).single().apply()
    }
}

trait StaticDBAsk[R] {
  def resultHandler: WrappedResultSet ⇒ R

  def request: SQL[Nothing, NoExtractor]

  def getListRO: List[R] =
    DB readOnly { implicit session ⇒
      request.map(resultHandler).list().apply()
    }

  def getSingleRO: Option[R] =
    DB readOnly { implicit session ⇒
      request.map(resultHandler).single().apply()
    }
}

trait DynamicDBUpdate {
  def update(sql: SQL[Nothing, NoExtractor]): Int =
    DB localTx { implicit session ⇒
      sql.update().apply()
    }

  def updateMultiple(sqlList: List[SQL[Nothing, NoExtractor]]): Int =
    DB localTx { implicit session ⇒
      sqlList.map(_.update().apply()).sum
    }
}
