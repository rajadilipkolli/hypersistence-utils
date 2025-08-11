package io.hypersistence.utils.hibernate.id;

import org.hibernate.HibernateException;
import org.hibernate.MappingException;
import org.hibernate.annotations.GenericGenerator;
import org.hibernate.boot.model.naming.Identifier;
import org.hibernate.boot.model.relational.Database;
import org.hibernate.boot.model.relational.QualifiedName;
import org.hibernate.boot.model.relational.QualifiedNameParser;
import org.hibernate.boot.model.relational.SqlStringGenerationContext;
import org.hibernate.dialect.Dialect;
import org.hibernate.engine.jdbc.env.spi.JdbcEnvironment;
import org.hibernate.engine.jdbc.spi.JdbcCoordinator;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.id.BulkInsertionCapableIdentifierGenerator;
import org.hibernate.id.Configurable;
import org.hibernate.id.IdentifierGenerationException;
import org.hibernate.id.PersistentIdentifierGenerator;
import org.hibernate.id.enhanced.Optimizer;
import org.hibernate.id.enhanced.SequenceStructure;
import org.hibernate.generator.GeneratorCreationContext;
import org.hibernate.internal.util.config.ConfigurationHelper;
import org.hibernate.service.ServiceRegistry;
import org.hibernate.type.Type;

import java.io.Serializable;
import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A sequence generator that uses a recursive query to fetch multiple
 * values from a sequence in a single database access.
 *
 * <h2>Configuration</h2>
 * <pre><code>
 * &commat;Id
 * &commat;BatchSequence(name = "SOME_SEQUENCE_NAME", fetch_size = SOME_FETCH_SIZE_VALUE)
 * private Long someColumnName;
 * </code></pre>
 *
 * <h2>SQL</h2>
 * Per default the generated SELECT will look something like this
 * <pre><code>
 * WITH RECURSIVE t(n) AS (
 *   SELECT 1
 *     UNION ALL
 *   SELECT n + 1
 *   FROM t
 *   WHERE n &lt; ?)
 * SELECT nextval(seq_xxx)
 *   FROM t;
 * </code></pre>
 *
 * <h3>DB2</h3>
 * For DB2 the generated SELECT will look something like this
 * <pre><code>
 * WITH t(n) AS (
 *   SELECT 1 AS n
 *     FROM (VALUES 1)
 *       UNION ALL
 *     SELECT n + 1 AS n
 *       FROM t
 *      WHERE n &lt; ?)
 * SELECT next value for SEQ_CHILD_ID AS n
 *   FROM t;
 * </code></pre>
 *
 * <h3>HSQLDB</h3>
 * For HSQLDB the generated SELECT will look something like this
 * <pre><code>
 * SELECT next value for seq_parent_id
 *   FROM UNNEST(SEQUENCE_ARRAY(1, ?, 1));
 * </code></pre>
 *
 * <h3>Oracle</h3>
 * For Oracle the generated SELECT will look something like this
 * because Oracle does not support using recursive common table
 * expressions to fetch multiple values from a sequence.
 * <pre><code>
 * SELECT seq_xxx.nextval
 * FROM dual
 * CONNECT BY rownum &lt;= ?
 * </code></pre>
 *
 * <h3>SQL Server</h3>
 * For SQL Server the generated SELECT will look something like this
 * <pre><code>
 * WITH t(n) AS (
 *   SELECT 1 AS n
 *     UNION ALL
 *   SELECT n + 1 AS n
 *     FROM t
 *    WHERE n &lt; ?)
 * SELECT NEXT VALUE FOR seq_xxx AS n
 *   FROM t
 * </code></pre>
 *
 * <h3>Firebird</h3>
 * For Firebird the generated SELECT will look something like this
 * <pre><code>
 * WITH RECURSIVE t(n, level_num) AS (
 *   SELECT NEXT VALUE FOR seq_xxx AS n, 1 AS level_num
 *   FROM rdb$database
 *     UNION ALL
 *   SELECT NEXT VALUE FOR seq_xxx AS n, level_num + 1 AS level_num
 *     FROM t
 *    WHERE level_num &lt; ?)
 * SELECT n
 *   FROM t
 * </code></pre>
 *
 * <h2>Database Support</h2>
 * The following RDBMS have been verified to work
 * <ul>
 *  <li>DB2</li>
 *  <li>Firebird</li>
 *  <li>Oracle</li>
 *  <li>H2</li>
 *  <li>HSQLDB</li>
 *  <li>MariaDB</li>
 *  <li>Postgres</li>
 *  <li>SQL Sever</li>
 * </ul>
 * <p>
 * In theory any RDBMS that supports {@code WITH RECURSIVE} and
 * sequences is supported.
 * <p>
 * For more details about how to use it, check out <a href="https://vladmihalcea.com/hibernate-batch-sequence-generator/">this article</a> on <a href="https://vladmihalcea.com/">vladmihalcea.com</a>.
 *
 * @author Philippe Marschall
 * @since 2.14.0
 */
public class BatchSequenceGenerator implements BulkInsertionCapableIdentifierGenerator, PersistentIdentifierGenerator, Configurable {

    /**
     * Indicates the name of the sequence to use, mandatory.
     * 
     * @deprecated use {@link BatchSequence}
     */
    @Deprecated
    public static final String SEQUENCE_PARAM = "sequence";

    /**
     * Indicates how many sequence values to fetch at once. The default value is {@link #DEFAULT_FETCH_SIZE}.
     * 
     * @deprecated use {@link BatchSequence}
     */
    @Deprecated
    public static final String FETCH_SIZE_PARAM = "fetch_size";

    /**
     * The default value for {@link #FETCH_SIZE_PARAM}.
     */
    public static final int DEFAULT_FETCH_SIZE = 10;

    private final Lock lock = new ReentrantLock();

    // Initialized during configure phase
    private int fetchSize;

    private QualifiedName sequenceName;

    private IdentifierExtractor identifierExtractor;

    private SequenceStructure sequenceStructure;

    // Initialized during initialize phase
    private String select;

    private IdentifierPool identifierPool;

    /**
     * Called when {@link BatchSequence} is used.
     * 
     * @param annotation meta annotation for configuration
     */
    public BatchSequenceGenerator(BatchSequence annotation,
                    Member annotatedMember,
                    GeneratorCreationContext context) {
      JdbcEnvironment jdbcEnvironment = context.getServiceRegistry().getService(JdbcEnvironment.class);
      this.sequenceName = determineSequenceName(annotation, jdbcEnvironment);
      this.fetchSize = annotation.fetchSize();
      
      Class<?> type = getType(annotatedMember);
      this.identifierExtractor = IdentifierExtractor.getIdentifierExtractor(type);
      this.sequenceStructure = this.buildSequenceStructure(type, sequenceName);
    }

    @Override
    public void configure(GeneratorCreationContext creationContext, Properties params)
                    throws MappingException {

        if (this.sequenceName == null) {
            // not initialized in constructor
            JdbcEnvironment jdbcEnvironment = creationContext.getServiceRegistry().getService(JdbcEnvironment.class);
            
            this.sequenceName = determineSequenceName(params, jdbcEnvironment);
            this.fetchSize = determineFetchSize(params);

            Class<?> numberType = creationContext.getType().getReturnedClass();
            this.identifierExtractor = IdentifierExtractor.getIdentifierExtractor(numberType);
            this.sequenceStructure = this.buildSequenceStructure(numberType, sequenceName);

        }
    }

    private static Class<?> getType(Member annotatedMember) {
        if (annotatedMember instanceof Field) {
            return ((Field) annotatedMember).getType();
        } else if (annotatedMember instanceof Method) {
            return ((Method) annotatedMember).getReturnType();
        } else {
            throw new IllegalArgumentException("unknown member type: " + annotatedMember);
        }
    }

	@Override
	public void initialize(SqlStringGenerationContext context) {
        Dialect dialect = context.getDialect();
        String sequenceNextValString = dialect
            .getSequenceSupport()
            .getSelectSequenceNextValString(
                context.format(this.sequenceName)
            );
		
        this.identifierPool = IdentifierPool.empty();
        this.sequenceStructure.initialize(context);

        this.select = buildSelect(sequenceNextValString, dialect);
    }

    private static String buildSelect(String nextValString, Dialect dialect) {
        if (dialect instanceof org.hibernate.dialect.OracleDialect) {
            return "SELECT " + nextValString + " FROM dual CONNECT BY rownum <= ?";
        }
        if (dialect instanceof org.hibernate.dialect.SQLServerDialect) {
            // No RECURSIVE
            return "WITH t(n) AS ( "
            + "SELECT 1 AS n "
            + "UNION ALL "
            +"SELECT n + 1 AS n FROM t WHERE n < ?) "
            // sequence generation outside of WITH
            + "SELECT " + nextValString + " AS n FROM t";
        }
        if (dialect instanceof org.hibernate.dialect.DB2Dialect) {
            // No RECURSIVE
            return "WITH t(n) AS ( "
            + "SELECT 1 AS n "
            // difference
            + "FROM (VALUES 1) "
            + "UNION ALL "
            +"SELECT n + 1 AS n FROM t WHERE n < ?) "
            // sequence generation outside of WITH
            + "SELECT " + nextValString + " AS n FROM t";
        }
        if (dialect instanceof org.hibernate.dialect.HSQLDialect) {
            // https://stackoverflow.com/questions/44472280/cte-based-sequence-generation-with-hsqldb/52329862
            return "SELECT " + nextValString + " FROM UNNEST(SEQUENCE_ARRAY(1, ?, 1))";
        }
        if(dialect instanceof org.hibernate.dialect.PostgreSQLDialect) {
            return "SELECT " + nextValString + " FROM generate_series(1, ?)";
        }
        return "WITH RECURSIVE t(n) AS ("
        + "SELECT 1 "
        + "UNION ALL "
        + "SELECT n + 1"
        + " FROM t "
        + " WHERE n < ?) "
        + "SELECT " + nextValString + " FROM t";
    }

    private SequenceStructure buildSequenceStructure(Class<?> type, QualifiedName sequenceName) {
        return new SequenceStructure("orm", sequenceName, 1, 1, type);
    }

	/**
	 * Determine the name of the sequence (or table if this resolves to a physical table)
	 * to use.
	 * <p/>
	 * Called during {@linkplain #configure configuration}.
	 *
	 * @param params The params supplied in the generator config (plus some standard useful extras).
	 * @param jdbcEnv The JdbcEnvironment
	 * @return The sequence name
	 */
	private static QualifiedName determineSequenceName(
			Properties params, JdbcEnvironment jdbcEnv) {
        String sequenceName = params.getProperty(SEQUENCE_PARAM);
        if (sequenceName == null) {
            throw new MappingException("no squence name specified");
        }

		final Identifier catalog = jdbcEnv.getIdentifierHelper().toIdentifier(params.getProperty(CATALOG));
		final Identifier schema =  jdbcEnv.getIdentifierHelper().toIdentifier(params.getProperty(SCHEMA));

        if(sequenceName.contains(".")) {
            return QualifiedNameParser.INSTANCE.parse(sequenceName);
        }

        return new QualifiedNameParser.NameParts(
            catalog,
            schema,
            jdbcEnv.getIdentifierHelper().toIdentifier( sequenceName )
            );
    }

    private static QualifiedName determineSequenceName(
                    BatchSequence annotation, JdbcEnvironment jdbcEnv) {
        String sequenceName = annotation.name();
        if (sequenceName == null) {
            throw new MappingException("no squence name specified");
        }

        final Identifier catalog = jdbcEnv.getIdentifierHelper().toIdentifier(annotation.catalog());
        final Identifier schema =  jdbcEnv.getIdentifierHelper().toIdentifier(annotation.schema());

        if(sequenceName.contains(".")) {
            return QualifiedNameParser.INSTANCE.parse(sequenceName);
        }

        return new QualifiedNameParser.NameParts(
                        catalog,
                        schema,
                        jdbcEnv.getIdentifierHelper().toIdentifier( sequenceName )
                        );
    }

    private static int determineFetchSize(Properties params) {
        int fetchSize = ConfigurationHelper.getInt(FETCH_SIZE_PARAM, params, DEFAULT_FETCH_SIZE);
        if (fetchSize <= 0) {
            throw new MappingException("fetch size must be positive");
        }
        return fetchSize;
    }

    @Override
    public boolean supportsBulkInsertionIdentifierGeneration() {
        return true;
    }

    @Override
    public String determineBulkInsertionIdentifierGenerationSelectFragment(SqlStringGenerationContext sqlStringGenerationContext) {
        return sqlStringGenerationContext.getDialect().getSequenceSupport().getSequenceNextValString(sqlStringGenerationContext.format(sequenceStructure.getPhysicalName()));
    }

    @Override
    public Serializable generate(SharedSessionContractImplementor session, Object object) throws HibernateException {
        this.lock.lock();
        try {
            if (this.identifierPool.isEmpty()) {
                this.identifierPool = this.replenishIdentifierPool(session);
            }
            return this.identifierPool.next();
        } finally {
            this.lock.unlock();
        }
    }

    private String getSequenceName() {
        return this.sequenceStructure.getPhysicalName().render();
    }

    @Override
    public void registerExportables(Database database) {
        this.sequenceStructure.registerExportables(database);
    }

    private IdentifierPool replenishIdentifierPool(SharedSessionContractImplementor session)
                    throws HibernateException {
        JdbcCoordinator coordinator = session.getJdbcCoordinator();
        List<Serializable> identifiers = new ArrayList<>(this.fetchSize);
        try (PreparedStatement statement = coordinator.getStatementPreparer().prepareStatement(this.select)) {
            statement.setFetchSize(this.fetchSize);
            statement.setInt(1, this.fetchSize);
            try (ResultSet resultSet = coordinator.getResultSetReturn().extract(statement, this.select)) {
                while (resultSet.next()) {
                    identifiers.add(this.identifierExtractor.extractIdentifier(resultSet));
                }
            }
        } catch (SQLException e) {
            throw session.getJdbcServices().getSqlExceptionHelper().convert(
                            e, "could not get next sequence value", this.select);
        }
        if (identifiers.size() != this.fetchSize) {
            throw new IdentifierGenerationException("expected " + this.fetchSize + " values from " + this.getSequenceName()
            + " but got " + identifiers.size());
        }
        return IdentifierPool.forList(identifiers);
    }

    @Override
    public Optimizer getOptimizer() {
        return null;
    }

    /**
     * Holds a number of prefetched identifiers.
     */
    static final class IdentifierPool {

        private final Iterator<Serializable> iterator;

        private IdentifierPool (List<Serializable> identifiers) {
            this.iterator = identifiers.iterator();
        }

        static IdentifierPool forList(List<Serializable> identifiers) {
            return new IdentifierPool(identifiers);
        }

        static IdentifierPool empty() {
            return new IdentifierPool(Collections.emptyList());
        }

        boolean isEmpty() {
            return !this.iterator.hasNext();
        }

        Serializable next() {
            return this.iterator.next();
        }

    }

    /**
     * Extracts a {@link Serializable} identifier from a {@link ResultSet}.
     *
     * @see org.hibernate.id.IntegralDataTypeHolder
     */
    enum IdentifierExtractor {

        INTEGER_IDENTIFIER_EXTRACTOR {
            @Override
            Serializable extractIdentifier(ResultSet resultSet) throws SQLException {
                int intValue = resultSet.getInt(1);
                if (resultSet.wasNull()) {
                    throw new IdentifierGenerationException("sequence returned null");
                }
                return intValue;
            }
        },

        LONG_IDENTIFIER_EXTRACTOR {
            @Override
            Serializable extractIdentifier(ResultSet resultSet) throws SQLException {
                long longValue = resultSet.getLong(1);
                if (resultSet.wasNull()) {
                    throw new IdentifierGenerationException("sequence returned null");
                }
                return longValue;
            }
        },

        BIG_INTEGER_IDENTIFIER_EXTRACTOR {
            @Override
            Serializable extractIdentifier(ResultSet resultSet) throws SQLException {
                BigDecimal bigDecimal = resultSet.getBigDecimal(1);
                if (resultSet.wasNull()) {
                    throw new IdentifierGenerationException("sequence returned null");
                }
                return bigDecimal.setScale(0, BigDecimal.ROUND_UNNECESSARY).toBigInteger();
            }
        },

        BIG_DECIMAL_IDENTIFIER_EXTRACTOR {
            @Override
            Serializable extractIdentifier(ResultSet resultSet) throws SQLException {
                BigDecimal bigDecimal = resultSet.getBigDecimal(1);
                if (resultSet.wasNull()) {
                    throw new IdentifierGenerationException("sequence returned null");
                }
                return bigDecimal;
            }
        };

        abstract Serializable extractIdentifier(ResultSet resultSet) throws SQLException;

        static IdentifierExtractor getIdentifierExtractor(Class<?> integralType) {
            if ((integralType == Integer.class) || (integralType == int.class)) {
                return INTEGER_IDENTIFIER_EXTRACTOR;
            }
            if ((integralType == Long.class) || (integralType == long.class)) {
                return LONG_IDENTIFIER_EXTRACTOR;
            }
            if (integralType == BigInteger.class) {
                return BIG_INTEGER_IDENTIFIER_EXTRACTOR;
            }
            if (integralType == BigDecimal.class) {
                return BIG_DECIMAL_IDENTIFIER_EXTRACTOR;
            }
            throw new IdentifierGenerationException("unsupported integral type: " + integralType);
        }

    }

    @Override
    public String toString() {
        // for debugging only
        return this.getClass().getSimpleName() + '(' + this.getSequenceName() + ')';
    }

}
