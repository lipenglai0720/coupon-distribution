package cn.quanshu.sharding;

import java.util.*;
import org.apache.shardingsphere.sharding.api.sharding.standard.*;

/** Fixed four-way hash. Changing shard count requires an explicit data migration. */
public final class UserHashAlgorithm implements StandardShardingAlgorithm<Comparable<?>> {
  public static int shard(long user) {
    return Math.floorMod(Long.hashCode(user), 4);
  }

  @Override
  public void init(Properties props) {}

  @Override
  public String doSharding(
      Collection<String> available, PreciseShardingValue<Comparable<?>> value) {
    String suffix = "_" + shard(((Number) value.getValue()).longValue());
    return available.stream().filter(name -> name.endsWith(suffix)).findFirst().orElseThrow();
  }

  @Override
  public Collection<String> doSharding(
      Collection<String> available, RangeShardingValue<Comparable<?>> value) {
    return available;
  }
}
