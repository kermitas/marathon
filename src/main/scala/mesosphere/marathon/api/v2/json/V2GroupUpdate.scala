package mesosphere.marathon.api.v2.json

import java.lang.{ Double => JDouble }
import com.wix.accord._
import com.wix.accord.dsl._
import mesosphere.marathon.state._
import mesosphere.marathon.api.v2.Validation._

import scala.reflect.ClassTag

case class V2GroupUpdate(
    id: Option[PathId],
    apps: Option[Set[AppDefinition]] = None,
    groups: Option[Set[V2GroupUpdate]] = None,
    dependencies: Option[Set[PathId]] = None,
    scaleBy: Option[Double] = None,
    version: Option[Timestamp] = None) {

  def groupId: PathId = id.getOrElse(throw new IllegalArgumentException("No group id was given!"))

  def apply(current: V2Group, timestamp: Timestamp): V2Group = {
    require(scaleBy.isEmpty, "To apply the update, no scale should be given.")
    require(version.isEmpty, "To apply the update, no version should be given.")
    val effectiveGroups = groups.fold(current.groups) { updates =>
      val currentIds = current.groups.map(_.id)
      val groupIds = updates.map(_.groupId.canonicalPath(current.id))
      val changedIds = currentIds.intersect(groupIds)
      val changedIdList = changedIds.toList
      val groupUpdates = changedIdList
        .flatMap(gid => current.groups.find(_.id == gid))
        .zip(changedIdList.flatMap(gid => updates.find(_.groupId.canonicalPath(current.id) == gid)))
        .map { case (group, groupUpdate) => groupUpdate(group, timestamp) }
      val groupAdditions = groupIds
        .diff(changedIds)
        .flatMap(gid => updates.find(_.groupId.canonicalPath(current.id) == gid))
        .map(update => update.toGroup(update.groupId.canonicalPath(current.id), timestamp))
      groupUpdates.toSet ++ groupAdditions
    }
    val effectiveApps: Set[AppDefinition] = apps.getOrElse(current.apps).map(toApp(current.id, _, timestamp))
    val effectiveDependencies = dependencies.fold(current.dependencies)(_.map(_.canonicalPath(current.id)))
    V2Group(current.id, effectiveApps, effectiveGroups, effectiveDependencies, timestamp)
  }

  def toApp(gid: PathId, app: AppDefinition, version: Timestamp): AppDefinition = {
    val appId = app.id.canonicalPath(gid)
    app.copy(id = appId, dependencies = app.dependencies.map(_.canonicalPath(gid)))
    // TODO AW: what about version? , version = version)
  }

  def toGroup(gid: PathId, version: Timestamp): V2Group = V2Group(
    gid,
    apps.getOrElse(Set.empty).map(toApp(gid, _, version)),
    groups.getOrElse(Set.empty).map(sub => sub.toGroup(sub.groupId.canonicalPath(gid), version)),
    dependencies.fold(Set.empty[PathId])(_.map(_.canonicalPath(gid))),
    version
  )
}

object V2GroupUpdate {
  def apply(id: PathId, apps: Set[AppDefinition]): V2GroupUpdate = {
    V2GroupUpdate(Some(id), if (apps.isEmpty) None else Some(apps))
  }
  def apply(id: PathId, apps: Set[AppDefinition], groups: Set[V2GroupUpdate]): V2GroupUpdate = {
    V2GroupUpdate(Some(id), if (apps.isEmpty) None else Some(apps), if (groups.isEmpty) None else Some(groups))
  }
  def empty(id: PathId): V2GroupUpdate = V2GroupUpdate(Some(id))

  implicit val v2GroupUpdateValidator: Validator[V2GroupUpdate] = validator[V2GroupUpdate] { group =>
    group is notNull

    group.version is hasOnlyOneDefinedOption
    group.scaleBy is hasOnlyOneDefinedOption

    group.id is valid
    group.apps is valid
    group.groups is valid
  }

  def hasOnlyOneDefinedOption[A <: Product: ClassTag, B]: Validator[A] =
    new Validator[A] {
      def apply(product: A) = {
        val n = product.productIterator.count {
          case Some(_) => true
          case _       => false
        }

        if (n <= 1)
          Success
        else
          Failure(Set(RuleViolation(product, s"not allowed in conjunction with other properties.", None)))
      }
    }
}
