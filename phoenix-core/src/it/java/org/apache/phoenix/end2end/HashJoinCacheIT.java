/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.phoenix.end2end;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Properties;
import java.util.Random;

import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.coprocessor.ObserverContext;
import org.apache.hadoop.hbase.coprocessor.RegionCoprocessorEnvironment;
import org.apache.hadoop.hbase.coprocessor.SimpleRegionObserver;
import org.apache.hadoop.hbase.regionserver.RegionScanner;
import org.apache.phoenix.cache.GlobalCache;
import org.apache.phoenix.cache.TenantCache;
import org.apache.phoenix.coprocessor.HashJoinCacheNotFoundException;
import org.apache.phoenix.hbase.index.util.ImmutableBytesPtr;
import org.apache.phoenix.join.HashJoinInfo;
import org.apache.phoenix.query.QueryServices;
import org.apache.phoenix.util.ByteUtil;
import org.apache.phoenix.util.PropertiesUtil;
import org.apache.phoenix.util.SchemaUtil;
import org.apache.phoenix.util.TestUtil;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

import com.google.common.collect.Lists;

import static org.apache.phoenix.util.TestUtil.TEST_PROPERTIES;
import static org.junit.Assert.fail;

@RunWith(Parameterized.class)
public class HashJoinCacheIT extends HashJoinIT {
    
    public HashJoinCacheIT(String[] indexDDL, String[] plans) throws Exception {
        super(indexDDL, plans);
    }
    
    protected String getTableName(Connection conn, String virtualName) throws Exception {
        String realName = super.getTableName(conn, virtualName);
        TestUtil.addCoprocessor(conn, SchemaUtil.normalizeFullTableName(realName), InvalidateHashCache.class);
        return realName;
    }
        
    @Parameters
    public static Collection<Object> data() {
        List<Object> testCases = Lists.newArrayList();
        testCases.add(new String[][] {
            {}, {
            /* 
             * testLeftJoinWithAggregation()
             *     SELECT i.name, sum(quantity) FROM joinOrderTable o 
             *     LEFT JOIN joinItemTable i ON o.item_id = i.item_id 
             *     GROUP BY i.name ORDER BY i.name
             */     
            "CLIENT PARALLEL 1-WAY FULL SCAN OVER " + JOIN_ORDER_TABLE_FULL_NAME + "\n" +
            "    SERVER AGGREGATE INTO DISTINCT ROWS BY [I.NAME]\n" +
            "CLIENT MERGE SORT\n" +
            "    PARALLEL LEFT-JOIN TABLE 0\n" +
            "        CLIENT PARALLEL 1-WAY FULL SCAN OVER " + JOIN_ITEM_TABLE_FULL_NAME,
            /* 
             * testLeftJoinWithAggregation()
             *     SELECT i.item_id iid, sum(quantity) q FROM joinOrderTable o 
             *     LEFT JOIN joinItemTable i ON o.item_id = i.item_id 
             *     GROUP BY i.item_id ORDER BY q DESC"
             */     
            "CLIENT PARALLEL 1-WAY FULL SCAN OVER " + JOIN_ORDER_TABLE_FULL_NAME + "\n" +
            "    SERVER AGGREGATE INTO DISTINCT ROWS BY [\"I.item_id\"]\n" +
            "CLIENT MERGE SORT\n" +
            "CLIENT SORTED BY [SUM(O.QUANTITY) DESC]\n" +
            "    PARALLEL LEFT-JOIN TABLE 0\n" +
            "        CLIENT PARALLEL 1-WAY FULL SCAN OVER " + JOIN_ITEM_TABLE_FULL_NAME + "\n" +
            "            SERVER FILTER BY FIRST KEY ONLY",
            /* 
             * testLeftJoinWithAggregation()
             *     SELECT i.item_id iid, sum(quantity) q FROM joinItemTable i 
             *     LEFT JOIN joinOrderTable o ON o.item_id = i.item_id 
             *     GROUP BY i.item_id ORDER BY q DESC NULLS LAST, iid
             */     
            "CLIENT PARALLEL 1-WAY FULL SCAN OVER " + JOIN_ITEM_TABLE_FULL_NAME + "\n" +
            "    SERVER FILTER BY FIRST KEY ONLY\n" +
            "    SERVER AGGREGATE INTO ORDERED DISTINCT ROWS BY [\"I.item_id\"]\n" +
            "CLIENT SORTED BY [SUM(O.QUANTITY) DESC NULLS LAST, \"I.item_id\"]\n" +
            "    PARALLEL LEFT-JOIN TABLE 0\n" +
            "        CLIENT PARALLEL 1-WAY FULL SCAN OVER " + JOIN_ORDER_TABLE_FULL_NAME,
            /* 
             * testRightJoinWithAggregation()
             *     SELECT i.name, sum(quantity) FROM joinOrderTable o 
             *     RIGHT JOIN joinItemTable i ON o.item_id = i.item_id 
             *     GROUP BY i.name ORDER BY i.name
             */
            "CLIENT PARALLEL 1-WAY FULL SCAN OVER " + JOIN_ITEM_TABLE_FULL_NAME + "\n" +
            "    SERVER AGGREGATE INTO DISTINCT ROWS BY [I.NAME]\n" +
            "CLIENT MERGE SORT\n" +
            "    PARALLEL LEFT-JOIN TABLE 0\n" +
            "        CLIENT PARALLEL 1-WAY FULL SCAN OVER " + JOIN_ORDER_TABLE_FULL_NAME,
            /*
             * testRightJoinWithAggregation()
             *     SELECT i.item_id iid, sum(quantity) q FROM joinOrderTable o 
             *     RIGHT JOIN joinItemTable i ON o.item_id = i.item_id 
             *     GROUP BY i.item_id ORDER BY q DESC NULLS LAST, iid
             */
            "CLIENT PARALLEL 1-WAY FULL SCAN OVER " + JOIN_ITEM_TABLE_FULL_NAME + "\n" +
            "    SERVER FILTER BY FIRST KEY ONLY\n" +
            "    SERVER AGGREGATE INTO ORDERED DISTINCT ROWS BY [\"I.item_id\"]\n" +
            "CLIENT SORTED BY [SUM(O.QUANTITY) DESC NULLS LAST, \"I.item_id\"]\n" +
            "    PARALLEL LEFT-JOIN TABLE 0\n" +
            "        CLIENT PARALLEL 1-WAY FULL SCAN OVER " + JOIN_ORDER_TABLE_FULL_NAME,
            /*
             * testJoinWithWildcard()
             *     SELECT * FROM joinItemTable LEFT JOIN joinSupplierTable supp 
             *     ON joinItemTable.supplier_id = supp.supplier_id 
             *     ORDER BY item_id
             */
            "CLIENT PARALLEL 1-WAY FULL SCAN OVER " + JOIN_ITEM_TABLE_FULL_NAME + "\n" + 
            "    PARALLEL LEFT-JOIN TABLE 0\n" +
            "        CLIENT PARALLEL 1-WAY FULL SCAN OVER " + JOIN_SUPPLIER_TABLE_FULL_NAME,
            /*
             * testJoinPlanWithIndex()
             *     SELECT item.item_id, item.name, supp.supplier_id, supp.name 
             *     FROM joinItemTable item LEFT JOIN joinSupplierTable supp 
             *     ON substr(item.name, 2, 1) = substr(supp.name, 2, 1) 
             *         AND (supp.name BETWEEN 'S1' AND 'S5') 
             *     WHERE item.name BETWEEN 'T1' AND 'T5'
             */
            "CLIENT PARALLEL 1-WAY FULL SCAN OVER " + JOIN_ITEM_TABLE_FULL_NAME + "\n" +
            "    SERVER FILTER BY (NAME >= 'T1' AND NAME <= 'T5')\n" +
            "    PARALLEL LEFT-JOIN TABLE 0\n" +
            "        CLIENT PARALLEL 1-WAY FULL SCAN OVER " + JOIN_SUPPLIER_TABLE_FULL_NAME + "\n" +
            "            SERVER FILTER BY (NAME >= 'S1' AND NAME <= 'S5')",
            /*
             * testJoinPlanWithIndex()
             *     SELECT item.item_id, item.name, supp.supplier_id, supp.name 
             *     FROM joinItemTable item INNER JOIN joinSupplierTable supp 
             *     ON item.supplier_id = supp.supplier_id 
             *     WHERE (item.name = 'T1' OR item.name = 'T5') 
             *         AND (supp.name = 'S1' OR supp.name = 'S5')
             */
            "CLIENT PARALLEL 1-WAY FULL SCAN OVER " + JOIN_ITEM_TABLE_FULL_NAME + "\n" +
            "    SERVER FILTER BY (NAME = 'T1' OR NAME = 'T5')\n" +
            "    PARALLEL INNER-JOIN TABLE 0\n" +
            "        CLIENT PARALLEL 1-WAY FULL SCAN OVER " + JOIN_SUPPLIER_TABLE_FULL_NAME + "\n" +
            "            SERVER FILTER BY (NAME = 'S1' OR NAME = 'S5')",
            /*
             * testJoinWithSkipMergeOptimization()
             *     SELECT s.name FROM joinItemTable i 
             *     JOIN joinOrderTable o ON o.item_id = i.item_id AND quantity < 5000 
             *     JOIN joinSupplierTable s ON i.supplier_id = s.supplier_id
             */
            "CLIENT PARALLEL 1-WAY FULL SCAN OVER " + JOIN_ITEM_TABLE_FULL_NAME + "\n" +
            "    PARALLEL INNER-JOIN TABLE 0 (SKIP MERGE)\n" +
            "        CLIENT PARALLEL 1-WAY FULL SCAN OVER " + JOIN_ORDER_TABLE_FULL_NAME + "\n" +
            "            SERVER FILTER BY QUANTITY < 5000\n" +
            "    PARALLEL INNER-JOIN TABLE 1\n" +
            "        CLIENT PARALLEL 1-WAY FULL SCAN OVER " + JOIN_SUPPLIER_TABLE_FULL_NAME + "\n" +
            "    DYNAMIC SERVER FILTER BY \"I.item_id\" IN (\"O.item_id\")",
            /*
             * testSelfJoin()
             *     SELECT i2.item_id, i1.name FROM joinItemTable i1 
             *     JOIN joinItemTable i2 ON i1.item_id = i2.item_id 
             *     ORDER BY i1.item_id
             */
            "CLIENT PARALLEL 1-WAY FULL SCAN OVER " + JOIN_ITEM_TABLE_FULL_NAME + "\n" +
            "    PARALLEL INNER-JOIN TABLE 0\n" +
            "        CLIENT PARALLEL 1-WAY FULL SCAN OVER " + JOIN_ITEM_TABLE_FULL_NAME + "\n" +
            "            SERVER FILTER BY FIRST KEY ONLY\n" +
            "    DYNAMIC SERVER FILTER BY \"I1.item_id\" IN (\"I2.item_id\")",
            /*
             * testSelfJoin()
             *     SELECT i1.name, i2.name FROM joinItemTable i1 
             *     JOIN joinItemTable i2 ON i1.item_id = i2.supplier_id 
             *     ORDER BY i1.name, i2.name
             */
            "CLIENT PARALLEL 1-WAY FULL SCAN OVER " + JOIN_ITEM_TABLE_FULL_NAME + "\n" +
            "    SERVER SORTED BY [I1.NAME, I2.NAME]\n" +
            "CLIENT MERGE SORT\n" +
            "    PARALLEL INNER-JOIN TABLE 0\n" +
            "        CLIENT PARALLEL 1-WAY FULL SCAN OVER " + JOIN_ITEM_TABLE_FULL_NAME + "\n" +
            "    DYNAMIC SERVER FILTER BY \"I1.item_id\" IN (\"I2.supplier_id\")",
            /*
             * testStarJoin()
             *     SELECT order_id, c.name, i.name iname, quantity, o.date 
             *     FROM joinOrderTable o 
             *     JOIN joinCustomerTable c ON o.customer_id = c.customer_id 
             *     JOIN joinItemTable i ON o.item_id = i.item_id 
             *     ORDER BY order_id
             */
            "CLIENT PARALLEL 1-WAY FULL SCAN OVER " + JOIN_ORDER_TABLE_FULL_NAME + "\n" +
            "    PARALLEL INNER-JOIN TABLE 0\n" +
            "        CLIENT PARALLEL 1-WAY FULL SCAN OVER " + JOIN_CUSTOMER_TABLE_FULL_NAME + "\n" +
            "    PARALLEL INNER-JOIN TABLE 1\n" +
            "        CLIENT PARALLEL 1-WAY FULL SCAN OVER " + JOIN_ITEM_TABLE_FULL_NAME,
            /*
             * testStarJoin()
             *     SELECT (*NO_STAR_JOIN*) order_id, c.name, i.name iname, quantity, o.date 
             *     FROM joinOrderTable o 
             *     JOIN joinCustomerTable c ON o.customer_id = c.customer_id 
             *     JOIN joinItemTable i ON o.item_id = i.item_id 
             *     ORDER BY order_id
             */
            "CLIENT PARALLEL 1-WAY FULL SCAN OVER " + JOIN_ITEM_TABLE_FULL_NAME + "\n" +
            "    SERVER SORTED BY [\"O.order_id\"]\n" +
            "CLIENT MERGE SORT\n" +
            "    PARALLEL INNER-JOIN TABLE 0\n" +
            "        CLIENT PARALLEL 1-WAY FULL SCAN OVER " + JOIN_ORDER_TABLE_FULL_NAME + "\n" +
            "            PARALLEL INNER-JOIN TABLE 0\n" +
            "                CLIENT PARALLEL 1-WAY FULL SCAN OVER " + JOIN_CUSTOMER_TABLE_FULL_NAME + "\n" +
            "    DYNAMIC SERVER FILTER BY \"I.item_id\" IN (\"O.item_id\")",
            /*
             * testSubJoin()
             *     SELECT * FROM joinCustomerTable c 
             *     INNER JOIN (joinOrderTable o 
             *         INNER JOIN (joinSupplierTable s 
             *             RIGHT JOIN joinItemTable i ON i.supplier_id = s.supplier_id)
             *         ON o.item_id = i.item_id)
             *     ON c.customer_id = o.customer_id
             *     WHERE c.customer_id <= '0000000005' 
             *         AND order_id != '000000000000003' 
             *         AND i.name != 'T3' 
             *     ORDER BY c.customer_id, i.name
             */
            "CLIENT PARALLEL 1-WAY RANGE SCAN OVER " + JOIN_CUSTOMER_TABLE_FULL_NAME + " [*] - ['0000000005']\n" +
            "    SERVER SORTED BY [\"C.customer_id\", I.NAME]\n" +
            "CLIENT MERGE SORT\n" +
            "    PARALLEL INNER-JOIN TABLE 0\n" +
            "        CLIENT PARALLEL 1-WAY FULL SCAN OVER " + JOIN_ORDER_TABLE_FULL_NAME + "\n" +
            "            SERVER FILTER BY \"order_id\" != '000000000000003'\n" +
            "            PARALLEL INNER-JOIN TABLE 0\n" +
            "                CLIENT PARALLEL 1-WAY FULL SCAN OVER " + JOIN_ITEM_TABLE_FULL_NAME + "\n" +
            "                    SERVER FILTER BY NAME != 'T3'\n" +
            "                    PARALLEL LEFT-JOIN TABLE 0\n" +
            "                        CLIENT PARALLEL 1-WAY FULL SCAN OVER " + JOIN_SUPPLIER_TABLE_FULL_NAME + "\n" +
            "    DYNAMIC SERVER FILTER BY \"C.customer_id\" IN (\"O.customer_id\")",
            /* 
             * testJoinWithSubqueryAndAggregation()
             *     SELECT i.name, sum(quantity) FROM joinOrderTable o 
             *     LEFT JOIN (SELECT name, item_id iid FROM joinItemTable) AS i 
             *     ON o.item_id = i.iid 
             *     GROUP BY i.name ORDER BY i.name
             */     
            "CLIENT PARALLEL 1-WAY FULL SCAN OVER " + JOIN_ORDER_TABLE_FULL_NAME + "\n" +
            "    SERVER AGGREGATE INTO DISTINCT ROWS BY [I.NAME]\n" +
            "CLIENT MERGE SORT\n" +
            "    PARALLEL LEFT-JOIN TABLE 0\n" +
            "        CLIENT PARALLEL 1-WAY FULL SCAN OVER " + JOIN_ITEM_TABLE_FULL_NAME,
            /* 
             * testJoinWithSubqueryAndAggregation()
             *     SELECT o.iid, sum(o.quantity) q 
             *     FROM (SELECT item_id iid, quantity FROM joinOrderTable) AS o 
             *     LEFT JOIN (SELECT item_id FROM joinItemTable) AS i 
             *     ON o.iid = i.item_id 
             *     GROUP BY o.iid ORDER BY q DESC                 
             */     
            "CLIENT PARALLEL 1-WAY FULL SCAN OVER " + JOIN_ORDER_TABLE_FULL_NAME + "\n" +
            "    SERVER AGGREGATE INTO DISTINCT ROWS BY [O.IID]\n" +
            "CLIENT MERGE SORT\n" +
            "CLIENT SORTED BY [SUM(O.QUANTITY) DESC]\n" +
            "    PARALLEL LEFT-JOIN TABLE 0 (SKIP MERGE)\n" +
            "        CLIENT PARALLEL 1-WAY FULL SCAN OVER " + JOIN_ITEM_TABLE_FULL_NAME + "\n" +
            "            SERVER FILTER BY FIRST KEY ONLY",
            /* 
             * testJoinWithSubqueryAndAggregation()
             *     SELECT i.iid, o.q 
             *     FROM (SELECT item_id iid FROM joinItemTable) AS i 
             *     LEFT JOIN (SELECT item_id iid, sum(quantity) q FROM joinOrderTable GROUP BY item_id) AS o 
             *     ON o.iid = i.iid 
             *     ORDER BY o.q DESC NULLS LAST, i.iid
             */     
            "CLIENT PARALLEL 1-WAY FULL SCAN OVER " + JOIN_ITEM_TABLE_FULL_NAME + "\n" +
            "    SERVER FILTER BY FIRST KEY ONLY\n" +
            "    SERVER SORTED BY [O.Q DESC NULLS LAST, I.IID]\n" +
            "CLIENT MERGE SORT\n" +
            "    PARALLEL LEFT-JOIN TABLE 0\n" +
            "        CLIENT PARALLEL 1-WAY FULL SCAN OVER " + JOIN_ORDER_TABLE_FULL_NAME + "\n" +
            "            SERVER AGGREGATE INTO DISTINCT ROWS BY [\"item_id\"]\n" +
            "        CLIENT MERGE SORT",
            /* 
             * testJoinWithSubqueryAndAggregation()
             *     SELECT i.iid, o.q 
             *     FROM (SELECT item_id iid, sum(quantity) q FROM joinOrderTable GROUP BY item_id) AS o 
             *     JOIN (SELECT item_id iid FROM joinItemTable) AS i 
             *     ON o.iid = i.iid 
             *     ORDER BY o.q DESC, i.iid
             */     
            "CLIENT PARALLEL 1-WAY FULL SCAN OVER " + JOIN_ITEM_TABLE_FULL_NAME + "\n" +
            "    SERVER FILTER BY FIRST KEY ONLY\n" +
            "    SERVER SORTED BY [O.Q DESC, I.IID]\n" +
            "CLIENT MERGE SORT\n" +
            "    PARALLEL INNER-JOIN TABLE 0\n" +
            "        CLIENT PARALLEL 1-WAY FULL SCAN OVER " + JOIN_ORDER_TABLE_FULL_NAME + "\n" +
            "            SERVER AGGREGATE INTO DISTINCT ROWS BY [\"item_id\"]\n" +
            "        CLIENT MERGE SORT",
            /*
             * testNestedSubqueries()
             *     SELECT * FROM (SELECT customer_id cid, name, phone, address, loc_id, date FROM joinCustomerTable) AS c 
             *     INNER JOIN (SELECT o.oid ooid, o.cid ocid, o.iid oiid, o.price * o.quantity, o.date odate, 
             *     qi.iiid iiid, qi.iname iname, qi.iprice iprice, qi.idiscount1 idiscount1, qi.idiscount2 idiscount2, qi.isid isid, qi.idescription idescription, 
             *     qi.ssid ssid, qi.sname sname, qi.sphone sphone, qi.saddress saddress, qi.sloc_id sloc_id 
             *         FROM (SELECT item_id iid, customer_id cid, order_id oid, price, quantity, date FROM joinOrderTable) AS o 
             *         INNER JOIN (SELECT i.iid iiid, i.name iname, i.price iprice, i.discount1 idiscount1, i.discount2 idiscount2, i.sid isid, i.description idescription, 
             *         s.sid ssid, s.name sname, s.phone sphone, s.address saddress, s.loc_id sloc_id 
             *             FROM (SELECT supplier_id sid, name, phone, address, loc_id FROM joinSupplierTable) AS s 
             *             RIGHT JOIN (SELECT item_id iid, name, price, discount1, discount2, supplier_id sid, description FROM joinItemTable) AS i 
             *             ON i.sid = s.sid) as qi 
             *         ON o.iid = qi.iiid) as qo 
             *     ON c.cid = qo.ocid 
             *     WHERE c.cid <= '0000000005' 
             *         AND qo.ooid != '000000000000003' 
             *         AND qo.iname != 'T3' 
             *     ORDER BY c.cid, qo.iname
             */
            "CLIENT PARALLEL 1-WAY RANGE SCAN OVER " + JOIN_CUSTOMER_TABLE_FULL_NAME + " [*] - ['0000000005']\n" +
            "    SERVER SORTED BY [C.CID, QO.INAME]\n" +
            "CLIENT MERGE SORT\n" +
            "    PARALLEL INNER-JOIN TABLE 0\n" +
            "        CLIENT PARALLEL 1-WAY FULL SCAN OVER " + JOIN_ORDER_TABLE_FULL_NAME + "\n" +
            "            SERVER FILTER BY \"order_id\" != '000000000000003'\n" +
            "            PARALLEL INNER-JOIN TABLE 0\n" +
            "                CLIENT PARALLEL 1-WAY FULL SCAN OVER " + JOIN_ITEM_TABLE_FULL_NAME + "\n" +
            "                    SERVER FILTER BY NAME != 'T3'\n" +
            "                    PARALLEL LEFT-JOIN TABLE 0\n" +
            "                        CLIENT PARALLEL 1-WAY FULL SCAN OVER " + JOIN_SUPPLIER_TABLE_FULL_NAME,
            /*
             * testJoinWithLimit()
             *     SELECT order_id, i.name, s.name, s.address, quantity 
             *     FROM joinSupplierTable s 
             *     LEFT JOIN joinItemTable i ON i.supplier_id = s.supplier_id 
             *     LEFT JOIN joinOrderTable o ON o.item_id = i.item_id LIMIT 4
             */
            "CLIENT SERIAL 1-WAY FULL SCAN OVER " + JOIN_SUPPLIER_TABLE_FULL_NAME + "\n" +
            "    SERVER 4 ROW LIMIT\n" +
            "CLIENT 4 ROW LIMIT\n" +
            "    PARALLEL LEFT-JOIN TABLE 0\n" +
            "        CLIENT PARALLEL 1-WAY FULL SCAN OVER "+ JOIN_ITEM_TABLE_FULL_NAME + "\n" +
            "    PARALLEL LEFT-JOIN TABLE 1(DELAYED EVALUATION)\n" +
            "        CLIENT PARALLEL 1-WAY FULL SCAN OVER "+ JOIN_ORDER_TABLE_FULL_NAME + "\n" +
            "    JOIN-SCANNER 4 ROW LIMIT",
            /*
             * testJoinWithLimit()
             *     SELECT order_id, i.name, s.name, s.address, quantity 
             *     FROM joinSupplierTable s 
             *     JOIN joinItemTable i ON i.supplier_id = s.supplier_id 
             *     JOIN joinOrderTable o ON o.item_id = i.item_id LIMIT 4
             */
            "CLIENT PARALLEL 1-WAY FULL SCAN OVER " + JOIN_SUPPLIER_TABLE_FULL_NAME + "\n" +
            "CLIENT 4 ROW LIMIT\n" +
            "    PARALLEL INNER-JOIN TABLE 0\n" +
            "        CLIENT PARALLEL 1-WAY FULL SCAN OVER "+ JOIN_ITEM_TABLE_FULL_NAME + "\n" +
            "    PARALLEL INNER-JOIN TABLE 1(DELAYED EVALUATION)\n" +
            "        CLIENT PARALLEL 1-WAY FULL SCAN OVER "+ JOIN_ORDER_TABLE_FULL_NAME + "\n" +
            "    DYNAMIC SERVER FILTER BY \"S.supplier_id\" IN (\"I.supplier_id\")\n" +
            "    JOIN-SCANNER 4 ROW LIMIT",
            /*
             * testJoinWithSetMaxRows()
             *     statement.setMaxRows(4);
             *     SELECT order_id, i.name, quantity FROM joinItemTable i
             *     JOIN joinOrderTable o ON o.item_id = i.item_id;
             *     SELECT o.order_id, i.name, o.quantity FROM joinItemTable i
             *     JOIN (SELECT order_id, item_id, quantity FROM joinOrderTable) o
             *     ON o.item_id = i.item_id;
             */
            "CLIENT PARALLEL 1-WAY FULL SCAN OVER " + JOIN_ITEM_TABLE_FULL_NAME + "\n" +
            "CLIENT 4 ROW LIMIT\n" +
            "    PARALLEL INNER-JOIN TABLE 0\n" +
            "        CLIENT PARALLEL 1-WAY FULL SCAN OVER "+ JOIN_ORDER_TABLE_FULL_NAME + "\n" +
            "    DYNAMIC SERVER FILTER BY \"I.item_id\" IN (\"O.item_id\")\n" +
            "    JOIN-SCANNER 4 ROW LIMIT",
            /*
             * testJoinWithOffset()
             *     SELECT order_id, i.name, s.name, s.address, quantity 
             *     FROM joinSupplierTable s 
             *     LEFT JOIN joinItemTable i ON i.supplier_id = s.supplier_id 
             *     LEFT JOIN joinOrderTable o ON o.item_id = i.item_id LIMIT 1 OFFSET 2
             */
            "CLIENT SERIAL 1-WAY FULL SCAN OVER " + JOIN_SUPPLIER_TABLE_FULL_NAME + "\n" +
            "    SERVER OFFSET 2\n" +
            "    SERVER 3 ROW LIMIT\n" +
            "CLIENT 1 ROW LIMIT\n" +
            "    PARALLEL LEFT-JOIN TABLE 0\n" +
            "        CLIENT PARALLEL 1-WAY FULL SCAN OVER "+ JOIN_ITEM_TABLE_FULL_NAME + "\n" +
            "    PARALLEL LEFT-JOIN TABLE 1(DELAYED EVALUATION)\n" +
            "        CLIENT PARALLEL 1-WAY FULL SCAN OVER "+ JOIN_ORDER_TABLE_FULL_NAME + "\n" +
            "    JOIN-SCANNER 3 ROW LIMIT",
            /*
             * testJoinWithOffset()
             *     SELECT order_id, i.name, s.name, s.address, quantity 
             *     FROM joinSupplierTable s 
             *     JOIN joinItemTable i ON i.supplier_id = s.supplier_id 
             *     JOIN joinOrderTable o ON o.item_id = i.item_id LIMIT 1 OFFSET 2
             */
            "CLIENT SERIAL 1-WAY FULL SCAN OVER " + JOIN_SUPPLIER_TABLE_FULL_NAME + "\n" +
            "    SERVER OFFSET 2\n" +
            "CLIENT 1 ROW LIMIT\n" +
            "    PARALLEL INNER-JOIN TABLE 0\n" +
            "        CLIENT PARALLEL 1-WAY FULL SCAN OVER "+ JOIN_ITEM_TABLE_FULL_NAME + "\n" +
            "    PARALLEL INNER-JOIN TABLE 1(DELAYED EVALUATION)\n" +
            "        CLIENT PARALLEL 1-WAY FULL SCAN OVER "+ JOIN_ORDER_TABLE_FULL_NAME + "\n" +
            "    DYNAMIC SERVER FILTER BY \"S.supplier_id\" IN (\"I.supplier_id\")\n" +
            "    JOIN-SCANNER 3 ROW LIMIT",
            }});
        return testCases;
    }
    
    @Test
    public void testInnerJoin() throws Exception {
		// it involves sequences which may be incremented on re-try when hash
		// cache is removed so this test may flap sometimes, so we don't need to
		// test it for this case.	
    }

	@Test
	public void testUpsertWithJoin() throws Exception {
		// TODO: We will enable this test once PHOENIX-3163
	}

    @Test
    public void testExpiredCache() throws Exception {
        Properties props = PropertiesUtil.deepCopy(TEST_PROPERTIES);
        props.setProperty(QueryServices.MAX_SERVER_CACHE_TIME_TO_LIVE_MS_ATTRIB, "1");
        Connection conn = DriverManager.getConnection(getUrl(), props);
        String tableName1 = getTableName(conn, JOIN_SUPPLIER_TABLE_FULL_NAME);
        String tableName2 = getTableName(conn, JOIN_ITEM_TABLE_FULL_NAME);
        String query = "SELECT item.\"item_id\", item.name, supp.\"supplier_id\", supp.name FROM " +
                tableName1 + " supp RIGHT JOIN " + tableName2 +
                " item ON item.\"supplier_id\" = supp.\"supplier_id\" ORDER BY \"item_id\"";
        try {
            PreparedStatement statement = conn.prepareStatement(query);
            ResultSet rs = statement.executeQuery();
            rs.next();
            fail("HashJoinCacheNotFoundException was not thrown or incorrectly handled");
        } catch (HashJoinCacheNotFoundException e) {
            //Expected exception
        }
    }

    public static class InvalidateHashCache extends SimpleRegionObserver {
        public static Random rand= new Random();
        public static List<ImmutableBytesPtr> lastRemovedJoinIds=new ArrayList<ImmutableBytesPtr>();
        @Override
        public RegionScanner preScannerOpen(final ObserverContext<RegionCoprocessorEnvironment> c, final Scan scan,
                final RegionScanner s) throws IOException {
            final HashJoinInfo joinInfo = HashJoinInfo.deserializeHashJoinFromScan(scan);
            if (joinInfo != null) {
                TenantCache cache = GlobalCache.getTenantCache(c.getEnvironment(), null);
                int count = joinInfo.getJoinIds().length;
                for (int i = 0; i < count; i++) {
                    ImmutableBytesPtr joinId = joinInfo.getJoinIds()[i];
                    if (!ByteUtil.contains(lastRemovedJoinIds,joinId)) {
                        lastRemovedJoinIds.add(joinId);
                        cache.removeServerCache(joinId);
                    }
                }
            }
            return s;
        }
        
    }
}
