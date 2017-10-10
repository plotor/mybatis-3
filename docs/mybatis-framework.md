[MyBatis](http://blog.mybatis.org/) 是一个简单、轻量，且强大的半自动化 ORM 框架。记得多年前第一次接触该框架的时候它的名字还是 iBatis，那时候刚接触到 java web 开发，倦于徒手写 JDBC 代码的枯燥，于是开始转战 ORM 框架。SSH 一直被认为是 java web 开发的三大件，所以 Hibernate 当然被视为首选，但是第一次使用的过程并不愉快，不需要自己写 SQL 的结果就是自动生成的 SQL 查询效率非常低效（原因应该在于我对这个框架不是很熟悉），于是放弃了对 Hibernate 的坚持，投入了 MyBatis 的怀抱，到今天已经使用 MyBatis 框架开发了好几个项目，虽然现在使用我司自研的 ORM 框架，但是鉴于对 MyBatis 的好感，决定利用空余时间对其实现机制做一番探究，于是有了接下去的几篇博文。

在正式开始之前，我们先以一个小例子回忆一下 MyBatis 的基本使用方式，MyBatis 目前已经同时支持 XML 和注解的方式编写 SQL 语句，虽然在 Spring 中，注解极大的提升了其使用体验，但是对于 MyBatis 来说，个人还是比较倾向于 XML 配置方式，这里我们选择 MyBatis 最基础的配置使用方式，不与 Spring 框架进行集成。

```xml
<!--结果集映射配置-->
<resultMap id="BaseResultMap" type="org.zhenchao.mybatis.entity.User">
    <id column="id" jdbcType="BIGINT" property="id"/>
    <result column="username" jdbcType="VARCHAR" property="username"/>
    <result column="password" jdbcType="CHAR" property="password"/>
    <result column="age" jdbcType="INTEGER" property="age"/>
    <result column="phone" jdbcType="VARCHAR" property="phone"/>
    <result column="email" jdbcType="VARCHAR" property="email"/>
</resultMap>

<!--自定义SQL语句-->
<select id="selectByIds" parameterType="java.util.List" resultMap="BaseResultMap">
    SELECT * FROM t_user WHERE id IN
    <foreach collection="ids" index="idx" item="itm" open="(" close=")" separator=",">
        #{itm}
    </foreach>
</select>
```

```java
SqlSessionFactory sessionFactory = new SqlSessionFactoryBuilder().build(Resources.getResourceAsStream("mybatis-config.xml"));
SqlSession sqlSession = sessionFactory.openSession();
UserMapper mapper = sqlSession.getMapper(UserMapper.class);
List<User> users = mapper.selectByIds(Arrays.asList(1L, 2L));
System.out.println(users);
sqlSession.close();
```

上面的例子展示了一个简单的查询多个 ID 用户详情的操作，MyBatis 可以基于我们传递的参数动态生成对应的 SQL 语句，后面对于源码的分析我们将一直围绕这个小例子，去探究 MyBatis 加载解析配置文件，绑定参数并执行方法对应的 SQL 语句，最后返回目标结果对象的过程。在这里我们先 MyBatis 的整个运行机制做一个简单的描述，先从整体上对该框架的执行过程有一个感知。

MyBatis 是基于配置的框架，它包含两大类型的配置文件，即 mybatis-config.xml 和 Mapper 接口对应的 SQL 语句配置，这里先约定一下，后续的博文中我们会将前者称为 __配置文件__，而将后者称为 __映射文件__。MyBatis 框架在启动时会加载并解析这两大类配置，整个过程对应上述示例中的第一行代码。当完成了对框架的初始化，接下来我们就可以创建会话，获取 Mapper 并执行目标数据库操作，这一过程可以用下面这幅时序图进行描述：

![image](https://github.com/procyon-lotor/procyon-lotor.github.io/blob/master/img/2017/mybatis.png?raw=false)

SqlSession 是 MyBatis 对外提供的执行数据库操作的统一接口，表示一次数据库会话，所以在具体操作数据库之前需要先打开一次会话。然后需要告知框架当前期望操作的具体 Mapper 接口，MyBatis 规定所有的 Mapper 接口需要定义成接口的形式，这主要是配置 jdk 自带的动态代理机制。MyBatis 会基于动态代理为当前 Mapper 接口创建对应的代理对象，并激活对象的 invoke 方法，在方法中会判断当前的 SQL 类型，并转给 SQL 执行器 Executor 去执行。Executor 会先尝试从框架自带的缓存（一级缓存和二级缓存）中获取当前查询对应的结果对象，如果缓存不命中则会执行数据库操作。对于查询操作来说，数据库返回的结果集与我们期望的结果对象之间还差那么一丢丢，这个时候 MyBatis 强大的结果集映射处理就可以大显身手，将结果集按照我们的配置映射成为具体的 java bean 对象（集合）返回。

这里只是大致对框架的运行机制做了一个概括，具体的实现细节后面会用专门的文档进行讲解，后面还会再次引用这幅时序图。下面来看一下 MyBatis 整体的架构设计，下图是笔者基于自己的理解并结合其他网友的总结绘制的一幅架构图，是对该框架的一个分层描绘：

![image](https://github.com/procyon-lotor/procyon-lotor.github.io/blob/master/img/2017/mybatis-framework.png?raw=false)

在接下去的一段时间，笔者将基于 3.4.x 版本，用 3 篇博文，从 __配置文件解析__、__映射文件解析__，以及 __SQL 具体执行过程__ 三个方面对 MyBatis 的实现内幕进行探究。拟定博文标题如下：

> 1. MyBatis源码解析：配置文件的加载与解析
> 2. MyBatis源码解析：映射文件的加载与解析
> 3. MyBatis源码解析：SQL语句的执行内幕

源码地址：[https://github.com/procyon-lotor/mybatis-3.git](https://github.com/procyon-lotor/mybatis-3.git)，分支名：zhenchao_reading
