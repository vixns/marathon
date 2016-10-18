package mesosphere.marathon
package state

import com.wix.accord._
import com.wix.accord.dsl._
import mesosphere.marathon.Protos.GroupDefinition
import mesosphere.marathon.api.v2.Validation._
import mesosphere.marathon.core.externalvolume.ExternalVolumes
import mesosphere.marathon.core.pod.PodDefinition
import mesosphere.marathon.plugin.{ Group => IGroup }
import mesosphere.marathon.state.Group._
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.stream._
import org.jgrapht.DirectedGraph
import org.jgrapht.alg.CycleDetector
import org.jgrapht.graph._

case class Group(
    id: PathId,
    apps: Map[AppDefinition.AppKey, AppDefinition] = defaultApps,
    pods: Map[PathId, PodDefinition] = defaultPods,
    groupsById: Map[Group.GroupKey, Group] = defaultGroups,
    dependencies: Set[PathId] = defaultDependencies,
    version: Timestamp = defaultVersion) extends MarathonState[GroupDefinition, Group] with IGroup {

  override def mergeFromProto(msg: GroupDefinition): Group = Group.fromProto(msg)
  override def mergeFromProto(bytes: Array[Byte]): Group = Group.fromProto(GroupDefinition.parseFrom(bytes))

  override def toProto: GroupDefinition = {
    GroupDefinition.newBuilder
      .setId(id.toString)
      .setVersion(version.toString)
      .addAllDeprecatedApps(apps.values.map(_.toProto))
      .addAllGroups(groups.map(_.toProto))
      .addAllDependencies(dependencies.map(_.toString))
      .build()
  }

  def findGroup(fn: Group => Boolean): Option[Group] = {
    def in(groups: List[Group]): Option[Group] = groups match {
      case head :: rest => if (fn(head)) Some(head) else in(rest).orElse(in(head.groups.toList))
      case Nil => None
    }
    if (fn(this)) Some(this) else in(groups.toList)
  }

  def app(appId: PathId): Option[AppDefinition] = group(appId.parent).flatMap(_.apps.get(appId))

  def pod(podId: PathId): Option[PodDefinition] = transitivePodsById.get(podId)

  def group(gid: PathId): Option[Group] = {
    if (id == gid) Some(this) else {
      val restPath = gid.restOf(id)
      groups.find(_.id.restOf(id).root == restPath.root).flatMap(_.group(gid))
    }
  }

  def updateApp(path: PathId, fn: Option[AppDefinition] => AppDefinition, timestamp: Timestamp): Group = {
    val groupId = path.parent
    makeGroup(groupId).update(timestamp) { group =>
      if (group.id == groupId) group.putApplication(fn(group.apps.get(path))) else group
    }
  }

  def updatePod(path: PathId, fn: Option[PodDefinition] => PodDefinition, timestamp: Timestamp): Group = {
    val groupId = path.parent
    makeGroup(groupId).update(timestamp) { group =>
      if (group.id == groupId) group.putPod(fn(group.pods.get(path))) else group
    }
  }

  def update(path: PathId, fn: Group => Group, timestamp: Timestamp): Group = {
    makeGroup(path).update(timestamp) { group =>
      if (group.id == path) fn(group) else group
    }
  }

  def updateApps(timestamp: Timestamp = Timestamp.now())(fn: AppDefinition => AppDefinition): Group = {
    update(timestamp) { group => group.copy(apps = group.apps.mapValues(fn)) }
  }

  def update(timestamp: Timestamp = Timestamp.now())(fn: Group => Group): Group = {
    def in(groups: List[Group]): List[Group] = groups match {
      case head :: rest => head.update(timestamp)(fn) :: in(rest)
      case Nil => Nil
    }
    fn(this.copy(
      groupsById = in(groups.toList).map(group => group.id -> group)(collection.breakOut),
      version = timestamp))
  }

  /** Removes the group with the given gid if it exists */
  def remove(gid: PathId, timestamp: Timestamp = Timestamp.now()): Group = {
    copy(groupsById = groups.filter(_.id != gid).map{ currentGroup =>
      val group = currentGroup.remove(gid, timestamp)
      group.id -> group
    }(collection.breakOut), version = timestamp)
  }

  /**
    * Add the given app definition to this group replacing any previously existing app definition with the same ID.
    * If a group exists with a conflicting ID which does not contain any app or pod definition, replace that as well.
    * See **very** similar logic in [[putPod]].
    */
  private def putApplication(appDef: AppDefinition): Group = {
    copy(
      // If there is a group with a conflicting id which contains no app or pod definitions,
      // replace it. Otherwise do not replace it. Validation should catch conflicting app/pod/group IDs later.
      groupsById = groupsById.filter {
        case (groupKey, group) =>
          group.id != appDef.id || group.containsApps || group.containsPods
      },
      // replace potentially existing app definition
      apps = apps + (appDef.id -> appDef)
    )
  }

  /**
    * Add the given pod definition to this group replacing any previously existing pod definition with the same ID.
    * If a group exists with a conflicting ID which does not contain any app or pod definition, replace that as well.
    * See **very** similar logic in [[putApplication]].
    */
  private def putPod(podDef: PodDefinition): Group = {
    copy(
      // If there is a group with a conflicting id which contains no app or pod definitions,
      // replace it. Otherwise do not replace it. Validation should catch conflicting app/pod/group IDs later.
      groupsById = groupsById.filter {
        case (groupKey, group) =>
          group.id != podDef.id || group.containsApps || group.containsPods
      },
      // replace potentially existing pod definition
      pods = pods + (podDef.id -> podDef)
    )
  }

  /**
    * Remove the app with the given id if it is a direct child of this group.
    *
    * Use together with [[mesosphere.marathon.state.Group!.update(timestamp*]].
    */
  def removeApplication(appId: PathId): Group = copy(apps = apps - appId)

  def removePod(podId: PathId): Group = copy(pods = pods - podId)

  def makeGroup(gid: PathId): Group = {
    if (gid.isEmpty) this //group already exists
    else {
      val (change, remaining) = groups.partition(_.id.restOf(id).root == gid.root)
      val toUpdate = change.headOption.getOrElse(Group.empty.copy(id = id.append(gid.rootPath)))
      this.copy(groupsById = (remaining + toUpdate.makeGroup(gid.child))
        .map(group => group.id -> group)(collection.breakOut))
    }
  }

  lazy val groups: Set[Group] = groupsById.values.toSet
  lazy val groupIds: Set[PathId] = groupsById.keySet

  lazy val transitiveAppsById: Map[PathId, AppDefinition] = this.apps ++ groups.flatMap(_.transitiveAppsById)
  lazy val transitiveApps: Set[AppDefinition] = transitiveAppsById.values.toSet
  lazy val transitiveAppIds: Set[PathId] = transitiveAppsById.keySet

  lazy val transitivePodsById: Map[PathId, PodDefinition] = this.pods ++ groups.flatMap(_.transitivePodsById)

  lazy val transitiveRunSpecsById: Map[PathId, RunSpec] = transitiveAppsById ++ transitivePodsById
  lazy val transitiveRunSpecs: Set[RunSpec] = transitiveRunSpecsById.values.toSet

  lazy val transitiveGroups: Set[Group] = groups.flatMap(_.transitiveGroups) + this

  lazy val transitiveAppGroups: Set[Group] = transitiveGroups.filter(_.apps.nonEmpty)

  lazy val applicationDependencies: List[(AppDefinition, AppDefinition)] = {
    var result = List.empty[(AppDefinition, AppDefinition)]
    val allGroups = transitiveGroups

    //group->group dependencies
    for {
      group <- allGroups
      dependencyId <- group.dependencies
      dependency <- allGroups.find(_.id == dependencyId)
      app <- group.transitiveApps
      dependentApp <- dependency.transitiveApps
    } result ::= app -> dependentApp

    //app->group/app dependencies
    for {
      group <- transitiveAppGroups
      app <- group.apps.values
      dependencyId <- app.dependencies
      dependentApp = transitiveAppsById.get(dependencyId).map(a => Set(a))
      dependentGroup = allGroups.find(_.id == dependencyId).map(_.transitiveApps)
      dependent <- dependentApp orElse dependentGroup getOrElse Set.empty
    } result ::= app -> dependent
    result
  }

  lazy val dependencyGraph: DirectedGraph[RunSpec, DefaultEdge] = {
    require(id.isRoot)
    val graph = new DefaultDirectedGraph[RunSpec, DefaultEdge](classOf[DefaultEdge])
    for (runnableSpec <- transitiveRunSpecsById.values) graph.addVertex(runnableSpec)
    for ((app, dependent) <- applicationDependencies) graph.addEdge(app, dependent)
    new UnmodifiableDirectedGraph(graph)
  }

  def runSpecsWithNoDependencies: Set[RunSpec] = {
    val g = dependencyGraph
    g.vertexSet.filter { v => g.outDegreeOf(v) == 0 }
  }

  def hasNonCyclicDependencies: Boolean = {
    !new CycleDetector[RunSpec, DefaultEdge](dependencyGraph).detectCycles()
  }

  /** @return true if and only if this group directly or indirectly contains app definitions. */
  def containsApps: Boolean = apps.nonEmpty || groups.exists(_.containsApps)

  def containsPods: Boolean = pods.nonEmpty || groups.exists(_.containsPods)

  def containsAppsOrPodsOrGroups: Boolean = apps.nonEmpty || groupsById.nonEmpty || pods.nonEmpty

  def withNormalizedVersion: Group = copy(version = Timestamp(0))

  def withoutChildren: Group = copy(apps = Map.empty, groupsById = Map.empty)

  /**
    * Identify an other group as the same, if id and version is the same.
    * Override the default equals implementation generated by scalac, which is very expensive.
    */
  override def equals(obj: Any): Boolean = {
    obj match {
      case that: Group => (that eq this) || (that.id == id && that.version == version)
      case _ => false
    }
  }

  /**
    * Compute the hashCode of an app only by id.
    * Override the default equals implementation generated by scalac, which is very expensive.
    */
  override def hashCode(): Int = id.hashCode()
}

object Group {
  type GroupKey = PathId

  def apply(
    id: PathId,
    apps: Map[AppDefinition.AppKey, AppDefinition],
    pods: Map[PathId, PodDefinition],
    groups: Set[Group],
    dependencies: Set[PathId],
    version: Timestamp): Group =
    new Group(id, apps, pods, groups.map(group => group.id -> group)(collection.breakOut), dependencies, version)

  def apply(
    id: PathId,
    apps: Map[AppDefinition.AppKey, AppDefinition],
    groups: Set[Group],
    dependencies: Set[PathId],
    version: Timestamp): Group =
    new Group(id, apps, Map.empty, groups.map(group => group.id -> group)(collection.breakOut), dependencies, version)

  def apply(
    id: PathId,
    apps: Map[AppDefinition.AppKey, AppDefinition],
    groups: Set[Group],
    dependencies: Set[PathId]): Group =
    new Group(id, apps, Map.empty, groups.map(group => group.id -> group)(collection.breakOut), dependencies)

  def apply(
    id: PathId,
    apps: Map[AppDefinition.AppKey, AppDefinition],
    groups: Set[Group]): Group =
    new Group(id, apps, Map.empty, groups.map(group => group.id -> group)(collection.breakOut))

  def apply(
    id: PathId,
    groups: Set[Group]): Group =
    new Group(id = id, groupsById = groups.map(group => group.id -> group)(collection.breakOut))

  def empty: Group = Group(PathId(Nil))
  def emptyWithId(id: PathId): Group = empty.copy(id = id)

  def fromProto(msg: GroupDefinition): Group = {
    new Group(
      id = msg.getId.toPath,
      apps = msg.getDeprecatedAppsList.map { proto =>
        val app = AppDefinition.fromProto(proto)
        app.id -> app
      }(collection.breakOut),
      pods = msg.getDeprecatedPodsList.map { proto =>
        val pod = PodDefinition.fromProto(proto)
        pod.id -> pod
      }(collection.breakOut),
      groupsById = msg.getGroupsList.map(fromProto).map(group => group.id -> group)(collection.breakOut),
      dependencies = msg.getDependenciesList.map(PathId.apply)(collection.breakOut),
      version = Timestamp(msg.getVersion)
    )
  }

  def defaultApps: Map[AppDefinition.AppKey, AppDefinition] = Map.empty
  val defaultPods = Map.empty[PathId, PodDefinition]
  def defaultGroups: Map[Group.GroupKey, Group] = Map.empty
  def defaultDependencies: Set[PathId] = Set.empty
  def defaultVersion: Timestamp = Timestamp.now()

  def validRootGroup(maxApps: Option[Int], enabledFeatures: Set[String]): Validator[Group] = {
    case object doesNotExceedMaxApps extends Validator[Group] {
      override def apply(group: Group): Result = {
        maxApps.filter(group.transitiveAppsById.size > _).map { num =>
          Failure(Set(RuleViolation(
            group,
            s"""This Marathon instance may only handle up to $num Apps!
                |(Override with command line option --max_apps)""".stripMargin, None)))
        } getOrElse Success
      }
    }

    def validNestedGroup(base: PathId): Validator[Group] = validator[Group] { group =>
      group.id is validPathWithBase(base)
      group.apps.values as "apps" is every(
        AppDefinition.validNestedAppDefinition(group.id.canonicalPath(base), enabledFeatures))
      group is noAppsAndPodsWithSameId
      group is noAppsAndGroupsWithSameName
      group is noPodsAndGroupsWithSameName
      group is conditional[Group](_.id.isRoot)(noCyclicDependencies)
      group.groups is every(valid(validNestedGroup(group.id.canonicalPath(base))))
    }

    // We do not want a "/value" prefix, therefore we do not create nested validators with validator[Group]
    // but chain the validators directly.
    doesNotExceedMaxApps and
      validNestedGroup(PathId.empty) and
      ExternalVolumes.validRootGroup()
  }

  private def noAppsAndPodsWithSameId: Validator[Group] =
    isTrue("Applications and Pods may not share the same id") { group =>
      val podIds = group.transitivePodsById.keySet
      val appIds = group.transitiveAppIds
      appIds.intersect(podIds).isEmpty
    }

  private def noAppsAndGroupsWithSameName: Validator[Group] =
    isTrue("Groups and Applications may not have the same identifier.") { group =>
      val groupIds = group.groupIds
      val clashingIds = groupIds.intersect(group.apps.keySet)
      clashingIds.isEmpty
    }

  private def noPodsAndGroupsWithSameName: Validator[Group] =
    isTrue("Groups and Pods may not have the same identifier.") { group =>
      val groupIds = group.groupIds
      val clashingIds = groupIds.intersect(group.pods.keySet)
      clashingIds.isEmpty
    }

  private def noCyclicDependencies: Validator[Group] =
    isTrue("Dependency graph has cyclic dependencies.") { _.hasNonCyclicDependencies }

}
