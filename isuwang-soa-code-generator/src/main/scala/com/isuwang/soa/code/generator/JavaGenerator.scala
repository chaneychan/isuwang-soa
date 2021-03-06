package com.isuwang.soa.code.generator

import java.io._
import java.util

import com.isuwang.soa.core.metadata.DataType.KIND
import com.isuwang.soa.core.metadata.TEnum.EnumItem
import com.isuwang.soa.core.metadata._

import scala.xml.Elem

/**
 * JAVA生成器
 *
 * @author tangliu
 * @date 15/9/8
 */
class JavaGenerator extends CodeGenerator {

  private def rootDir(rootDir: String, packageName: String): File = {
    val dir = rootDir + "/java-gen/" + packageName.replaceAll("[.]", "/")

    val file = new File(dir)

    if(!file.exists())
      file.mkdirs()

    return file
  }

  override def generate(services: util.List[Service], outDir: String): Unit = {

    val namespaces:util.Set[String] = new util.HashSet[String]();
    for (index <- (0 until services.size())) {
      val service = services.get(index)

      namespaces.add(service.getNamespace);

      for(enumIndex <- (0 until service.getEnumDefinitions.size())) {
        val enumDefinition = service.getEnumDefinitions.get(enumIndex);

        namespaces.add(enumDefinition.getNamespace)
      }

      for(structIndex <- (0 until service.getStructDefinitions.size())) {
        val structDefinition = service.getStructDefinitions.get(structIndex);

        namespaces.add(structDefinition.getNamespace)
      }

      //namespaces.add(service.namespace.substring(0, service.namespace.lastIndexOf(".")) + "Codec." + service.name)
    }

    for (index <- (0 until services.size())) {

      val service = services.get(index)
      val t1 = System.currentTimeMillis();
      println("=========================================================")
      println(s"服务名称:${service.name}")

      println(s"生成service:${service.name}.java")
      val serviceTemplate = new StringTemplate(toServiceTemplate(service))
      val writer = new PrintWriter(new File(rootDir(outDir, service.getNamespace), s"${service.name}.java"), "UTF-8")
      writer.write(serviceTemplate.toString())
      writer.close()
      println(s"生成service:${service.name}.java 完成")

      {
        toStructArrayBuffer(service.structDefinitions).map{(struct: Struct)=>{

          println(s"生成struct:${struct.name}.java")
          val domainTemplate = new StringTemplate(toDomainTemplate(struct))
          val domainWriter = new PrintWriter(new File(rootDir(outDir, struct.getNamespace), s"${struct.name}.java"), "UTF-8")
          domainWriter.write(domainTemplate.toString)
          domainWriter.close()
          println(s"生成struct:${struct.name}.java 完成")
        }
        }
      }

      {
       toTEnumArrayBuffer(service.enumDefinitions).map{(enum: TEnum)=>{

         println(s"生成Enum:${enum.name}.java")
         val enumTemplate = new StringTemplate(toEnumTemplate(enum))
         val enumWriter = new PrintWriter(new File(rootDir(outDir, enum.getNamespace), s"${enum.name}.java"), "UTF-8")
         enumWriter.write(enumTemplate.toString)
         enumWriter.close()
         println(s"生成Enum:${enum.name}.java 完成")
       }
       }
      }

      println(s"生成client:${service.name}Client.java")
      val clientTemplate = new StringTemplate(toClientTemplate(service, namespaces))
      val clientWriter = new PrintWriter(new File(rootDir(outDir, service.namespace.substring(0, service.namespace.lastIndexOf("."))), s"${service.name}Client.java"), "UTF-8")
      clientWriter.write(clientTemplate.toString())
      clientWriter.close()
      println(s"生成client:${service.name}Client.java 完成")


      println(s"生成Codec:${service.name}Codec.java")
      //because of some reason the response of each method is always optional, which is not true,so correct it here
      for (methodIndex <- (0 until service.getMethods.size())) {
        val method = service.getMethods().get(methodIndex)
        method.getResponse.getFields.get(0).setOptional(false);
      }
      val codecTemplate = new StringTemplate(new JavaClientGenerator().toCodecTemplate(service, namespaces))
      val codecWriter = new PrintWriter(new File(rootDir(outDir, service.namespace.substring(0, service.namespace.lastIndexOf("."))), s"${service.name}Codec.java"), "UTF-8")
      codecWriter.write(codecTemplate.toString())
      codecWriter.close()

      println(s"生成Codec:${service.name}Codec.java 完成")



      println(s"生成metadata:${service.namespace}.${service.name}.xml")
      new MetadataGenerator().generateXmlFile(service, outDir);
      println(s"生成metadata:${service.namespace}.${service.name}.xml 完成")

      println("==========================================================")
      val t2 = System.currentTimeMillis();
      println(s"生成耗时:${t2 - t1}ms")
      println(s"生成状态:完成")

    }

  }

  private def toClientTemplate(service: Service, namespaces:util.Set[String]): Elem = {
    return {
      <div>package {service.namespace.substring(0, service.namespace.lastIndexOf("."))};

        import com.isuwang.soa.core.*;
        import org.apache.thrift.*;
        import com.isuwang.soa.remoting.BaseServiceClient;
        import {service.namespace.substring(0, service.namespace.lastIndexOf(".")) + "." + service.name + "Codec.*"};

        public class {service.name}Client extends BaseServiceClient<block>

        public {service.name}Client() <block>
          super("{service.namespace}.{service.name}", "{service.meta.version}");
        </block>

        {
        toMethodArrayBuffer(service.methods).map{(method:Method)=>{
          <div>
            /**
            * {method.doc}
            **/
            public {toDataTypeTemplate(method.getResponse.getFields().get(0).getDataType)} {method.name}({toFieldArrayBuffer(method.getRequest.getFields).map{ (field: Field) =>{
            <div>{toDataTypeTemplate(field.getDataType())} {field.name}{if(field != method.getRequest.fields.get(method.getRequest.fields.size() - 1)) <span>,</span>}</div>}}}) throws TException<block>
            initContext("{method.name}");

            try <block>
               {method.getRequest.name} {method.getRequest.name} = new {method.getRequest.name}();
            {
            toFieldArrayBuffer(method.getRequest.getFields).map{(field: Field)=>{
              <div>{method.getRequest.name}.set{field.name.charAt(0).toUpper + field.name.substring(1)}({field.name});
              </div>
            }
            }
            }

            {method.response.name} response = sendBase({method.request.name}, new {method.response.name}(), new {method.request.name.charAt(0).toUpper + method.request.name.substring(1)}Serializer(), new {method.response.name.charAt(0).toUpper + method.response.name.substring(1)}Serializer());

               {
                toFieldArrayBuffer(method.getResponse.getFields()).map {(field:Field)=> {
                  <div>
                    {
                      if(field.getDataType.getKind == DataType.KIND.VOID) {
                        <div></div>
                      } else {
                        <div>
                          return response.getSuccess();
                        </div>
                      }
                    }
                  </div>
                }
                }
               }
            </block> finally <block>
              destoryContext();
            </block>
            </block>
          </div>
        }
        }
        }

        /**
        * getServiceMetadata
        **/
        public String getServiceMetadata() throws TException <block>
          initContext("getServiceMetadata");
          try <block>
            getServiceMetadata_args getServiceMetadata_args = new getServiceMetadata_args();
            getServiceMetadata_result response = sendBase(getServiceMetadata_args, new getServiceMetadata_result(), new GetServiceMetadata_argsSerializer(), new GetServiceMetadata_resultSerializer());
            return response.getSuccess();
          </block> finally <block>
            destoryContext();
          </block>
        </block>

        </block>
      </div>
    }
  }


  private def toEnumTemplate(enum: TEnum): Elem = {
    return {
      <div>package {enum.namespace};

        public enum {enum.name}<block>
        {
          toEnumItemArrayBuffer(enum.enumItems).map{(enumItem: EnumItem)=>{
          <div>
            /**
            *{enumItem.getDoc}
            **/
            {enumItem.label}({enumItem.value}){if(enumItem == enum.enumItems.get(enum.enumItems.size() - 1)) <div>;</div> else <div>,</div>}
          </div>
          }
          }
        }

        private final int value;

        private {enum.name}(int value)<block>
            this.value = value;
        </block>

        public int getValue()<block>
            return this.value;
        </block>

        public static {enum.name} findByValue(int value)<block>
            switch(value)<block>
            {
            toEnumItemArrayBuffer(enum.enumItems).map{(enumItem: EnumItem)=>{
               <div>
                 case {enumItem.value}:
                    return {enumItem.label};
               </div>
            }
            }
            }
               default:
                   return null;
            </block>
        </block>
        </block>
      </div>
    }
  }

  private def toDomainTemplate(struct: Struct): Elem = {
    return {
      <div>package {struct.namespace};

        import java.util.Optional;

        /**
        *{struct.doc}
        **/
        public class {struct.name}<block>
        {toFieldArrayBuffer(struct.getFields).map{(field : Field) =>{
          <div>
            /**
            *{field.doc}
            **/
            {if(field.isPrivacy)  <div>private</div> else <div>public</div>} {if(field.isOptional) <div>Optional{lt}</div>}{toDataTypeTemplate(field.getDataType)}{if(field.isOptional) <div>{gt}</div>} {field.name} {if(field.isOptional) <div>= Optional.empty()</div>};
            public {if(field.isOptional) <div>Optional{lt}</div>}{toDataTypeTemplate(field.getDataType)}{if(field.isOptional) <div>{gt}</div>} get{field.name.charAt(0).toUpper + field.name.substring(1)}()<block> return this.{field.name}; </block>
            public void set{field.name.charAt(0).toUpper + field.name.substring(1)}({if(field.isOptional) <div>Optional{lt}</div>}{toDataTypeTemplate(field.getDataType)}{if(field.isOptional) <div>{gt}</div>} {field.name})<block> this.{field.name} = {field.name}; </block>

            {if(field.dataType.kind == DataType.KIND.BOOLEAN) <div>public {if(field.isOptional) <div>Optional{lt}</div>}{toDataTypeTemplate(field.getDataType)}{if(field.isOptional) <div>{gt}</div>} is{field.name.charAt(0).toUpper + field.name.substring(1)}() <block>
            return this.{field.name};
          </block></div>}
          </div>
        }
        }
        }

        public String toString()<block>
          StringBuilder stringBuilder = new StringBuilder("<block>");
          {toFieldArrayBuffer(struct.getFields).map{(field : Field) =>{
                getToStringElement(field)
          }
          }
          }
          stringBuilder.deleteCharAt(stringBuilder.lastIndexOf(","));
          stringBuilder.append("</block>");

          return stringBuilder.toString();
        </block>
      </block>
      </div>
    }
  }

  private def toServiceTemplate(service:Service): Elem = {
    return {
      <div>
        package {service.namespace};

        import com.isuwang.soa.core.Processor;
        import com.isuwang.soa.core.Service;

        /**
        * {service.doc}
        **/
        @Service(version = "{service.meta.version}")
        @Processor(className = "{service.namespace.substring(0, service.namespace.lastIndexOf("service"))}{service.name}Codec$Processor")
        public interface {service.name} <block>
        {
        toMethodArrayBuffer(service.methods).map { (method: Method) =>
        {
          <div>
            /**
            * {method.doc}
            **/
            {toDataTypeTemplate(method.getResponse.getFields().get(0).getDataType)} {method.name}({toFieldArrayBuffer(method.getRequest.getFields).map{ (field: Field) =>{
            <div> {toDataTypeTemplate(field.getDataType())} {field.name}{if(field != method.getRequest.fields.get(method.getRequest.fields.size() - 1)) <span>,</span>}</div>}
          }}) throws com.isuwang.soa.core.SoaException;
          </div>
        }
        }
        }
        </block>
        </div>
    }
  }



  def toDataTypeTemplate(dataType:DataType): Elem = {
    dataType.kind match {
      case KIND.VOID => <div>void</div>
      case KIND.BOOLEAN => <div>Boolean</div>
      case KIND.BYTE => <div>Byte</div>
      case KIND.SHORT => <div>Short</div>
      case KIND.INTEGER => <div>Integer</div>
      case KIND.LONG => <div>Long</div>
      case KIND.DOUBLE => <div>Double</div>
      case KIND.STRING => <div>String</div>
      case KIND.BINARY => <div>java.nio.ByteBuffer</div>
      case KIND.DATE => <div>java.util.Date</div>
      case KIND.MAP =>
        return {<div>java.util.Map{lt}{toDataTypeTemplate(dataType.getKeyType())}, {toDataTypeTemplate(dataType.getValueType())}{gt}</div>}
      case KIND.LIST =>
        return {<div>java.util.List{lt}{toDataTypeTemplate(dataType.getValueType())}{gt}</div>}
      case KIND.SET =>
        return {<div>java.util.Set{lt}{toDataTypeTemplate(dataType.getValueType())}{gt}</div>}
      case KIND.ENUM =>
        val ref = dataType.getQualifiedName();
        return {<div>{ref}</div>}
      case KIND.STRUCT =>
        val ref = dataType.getQualifiedName();
        return {<div>{ref}</div>}
    }
  }

  def getToStringElement(field: Field): Elem = {
    <div>stringBuilder.append("\"").append("{field.name}").append("\":{if(field.dataType.kind == DataType.KIND.STRING) <div>\"</div>}").append({getToStringByDataType(field)}).append("{if(field.dataType.kind == DataType.KIND.STRING) <div>\"</div>},");
    </div>
  }

  def getToStringByDataType(field: Field):Elem = {

    if(field.getDoc != null && field.getDoc.toLowerCase.contains("@logger(level=\"off\")"))
       <div>"LOGGER_LEVEL_OFF"</div>
    else if(field.isOptional)
       <div>this.{field.name}.isPresent()?this.{field.name}.get(){if(field.dataType.kind == KIND.STRUCT) <div>.toString()</div>}:null</div>
    else
       <div>this.{field.name}{if(field.dataType.kind == KIND.STRUCT) <div>.toString()</div>}</div>
  }
}