# ADR 0006: Partition key strategy

## Context

Every table has a primary key: a partition key and, optionally, a sort key ([ADR 0004](0004-api-protocol.md)).
In sharded mode the Coordinator hashes a routing key to find the shard for a record ([ADR 0005](0005-hash-function-and-vnodes.md)).
This ADR decides what the routing key is made of, and what the partition key and the sort key are used for.

Example: a table `orders` with partition key `customer_id` and sort key `order_id`.
The question is whether all orders of customer `42` should be on one shard, or spread over all shards.

The client decides which attributes are the partition key and the sort key of a table, depending on their use case.
The store only decides how it uses them.

## Alternatives considered

- *Hash the partition key and the sort key together*: every record is hashed on its own, so the records spread evenly over the shards even if one partition is very big.
  Rejected because the records of one partition end up on different shards.
  Then no shard holds the whole partition, so the sort key can't sort the records of a partition, and a future query like "all orders of customer `42`" would have to ask every shard.
  The two keys would also have to be joined into one string carefully, so that `("ab", "c")` and `("a", "bc")` don't give the same routing key.
- *Partition key made of several fields* (Cassandra allows this): more flexible, but it makes the table definition, the API and the routing key more complex.
  Rejected to keep things simple.
  A client that needs it can join the fields into one value itself.
- *Sort key made of several attributes, each sorted ASC or DESC* (Cassandra-style, for example `CLUSTERING ORDER BY (date DESC, order_id ASC)`): more flexible ordering.
  Rejected because it is more complex and not required: M2 asks only for "partition key + sort key".
  It would need a list of sort key attributes in the table definition, several sort key values in the URL, and a comparator for each direction.
  In M2 the order is not even visible yet, because there are no range queries.
- *Include the table name in the routing key*: each table would be spread over the shards on its own.
  Rejected to keep things simple.
  Without it, records with the same partition key value in different tables land on the same shard, which is harmless.
- *Hash only the partition key*: all records of a partition are on the same shard, and the sort key orders and identifies the records inside the partition.
  This is how Cassandra (partition key + clustering key) and DynamoDB (partition key + sort key) work.

## Decision

**Only the partition key is hashed.** The routing key is the value of the partition key.

- **Partition key**: exactly one attribute.
  It decides on which shard a record is stored.
- **Sort key** (optional): one attribute, always sorted ascending, as in DynamoDB.
  It sorts the records inside a partition and, together with the partition key, makes each record unique.
- **YCSB**: YCSB's key (for example `user6284781860667377211`) is the partition key of the table `usertable`, which has no sort key.

## Consequences

- All records with the same partition key are on the same shard, sorted by the sort key in ascending order, as in DynamoDB.
- A future query over one partition (for example "the orders of customer `42`, sorted by `order_id`") can be answered by one shard.
  In M2 there are no such queries ([ADR 0004](0004-api-protocol.md): no range queries), so for now the sort key only makes records unique, and the order is not visible yet.
- In M5 (rebalancing), a whole partition moves to the new shard together.
- For YCSB this choice makes no difference: there is no sort key, so the partition key is the whole primary key.
- Keys are strings (ADR 0004), so records are sorted as text: `order10` comes before `order2`.
  A client that wants numeric order has to pad the numbers (`order02`, `order10`).
- A very big or very busy partition (a "hot partition") puts all its load on one shard.
  The store does not prevent this.
  Choosing a partition key with many different values is up to the client, as in DynamoDB.
