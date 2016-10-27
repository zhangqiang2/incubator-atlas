/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.atlas.model.typedef;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.bind.annotation.XmlSeeAlso;

import org.apache.atlas.model.PList;
import org.apache.atlas.model.SearchFilter.SortType;
import org.apache.commons.collections.CollectionUtils;
import org.codehaus.jackson.annotate.JsonAutoDetect;
import static org.codehaus.jackson.annotate.JsonAutoDetect.Visibility.PUBLIC_ONLY;
import static org.codehaus.jackson.annotate.JsonAutoDetect.Visibility.NONE;
import org.codehaus.jackson.annotate.JsonIgnoreProperties;
import org.codehaus.jackson.map.annotate.JsonSerialize;


/**
 * class that captures details of a classification-type.
 */
@JsonAutoDetect(getterVisibility=PUBLIC_ONLY, setterVisibility=PUBLIC_ONLY, fieldVisibility=NONE)
@JsonSerialize(include=JsonSerialize.Inclusion.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown=true)
@XmlRootElement
@XmlAccessorType(XmlAccessType.PROPERTY)
public class AtlasClassificationDef extends AtlasStructDef implements java.io.Serializable {
    private static final long serialVersionUID = 1L;

    private Set<String> superTypes;


    public AtlasClassificationDef() {
        super();

        setSuperTypes(null);
    }

    public AtlasClassificationDef(String name) {
        this(name, null, null, null, null);
    }

    public AtlasClassificationDef(String name, String description) {
        this(name, description, null, null, null);
    }

    public AtlasClassificationDef(String name, String description, String typeVersion) {
        this(name, description, typeVersion, null, null);
    }

    public AtlasClassificationDef(String name, String description, String typeVersion,
                                  List<AtlasAttributeDef> attributeDefs) {
        this(name, description, typeVersion, attributeDefs, null);
    }

    public AtlasClassificationDef(String name, String description, String typeVersion,
                                  List<AtlasAttributeDef> attributeDefs, Set<String> superTypes) {
        super(name, description, typeVersion, attributeDefs);

        setSuperTypes(superTypes);
    }

    public AtlasClassificationDef(AtlasClassificationDef other) {
        super(other);

        setSuperTypes(other != null ? other.getSuperTypes() : null);
    }

    public Set<String> getSuperTypes() {
        return superTypes;
    }

    public void setSuperTypes(Set<String> superTypes) {
        if (superTypes != null && this.superTypes == superTypes) {
            return;
        }

        if (CollectionUtils.isEmpty(superTypes)) {
            this.superTypes = new HashSet<String>();
        } else {
            this.superTypes = new HashSet<String>(superTypes);
        }
    }

    public boolean hasSuperType(String typeName) {
        return hasSuperType(superTypes, typeName);
    }

    public void addSuperType(String typeName) {
        Set<String> s = this.superTypes;

        if (!hasSuperType(s, typeName)) {
            s = new HashSet<String>(s);

            s.add(typeName);

            this.superTypes = s;
        }
    }

    public void removeSuperType(String typeName) {
        Set<String> s = this.superTypes;

        if (hasSuperType(s, typeName)) {
            s = new HashSet<String>(s);

            s.remove(typeName);

            this.superTypes = s;
        }
    }

    private static boolean hasSuperType(Set<String> superTypes, String typeName) {
        return superTypes != null && typeName != null && superTypes.contains(typeName);
    }

    @Override
    public StringBuilder toString(StringBuilder sb) {
        if (sb == null) {
            sb = new StringBuilder();
        }

        sb.append("AtlasClassificationDef{");
        super.toString(sb);
        sb.append(", superTypes=[");
        dumpObjects(superTypes, sb);
        sb.append("]");
        sb.append('}');

        return sb;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) { return true; }
        if (o == null || getClass() != o.getClass()) { return false; }
        if (!super.equals(o)) { return false; }

        AtlasClassificationDef that = (AtlasClassificationDef) o;

        if (superTypes != null ? !superTypes.equals(that.superTypes) : that.superTypes != null) { return false; }

        return true;
    }

    @Override
    public int hashCode() {
        int result = super.hashCode();
        result = 31 * result + (superTypes != null ? superTypes.hashCode() : 0);
        return result;
    }

    @Override
    public String toString() {
        return toString(new StringBuilder()).toString();
    }


    /**
     * REST serialization friendly list.
     */
    @JsonAutoDetect(getterVisibility=PUBLIC_ONLY, setterVisibility=PUBLIC_ONLY, fieldVisibility=NONE)
    @JsonSerialize(include=JsonSerialize.Inclusion.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown=true)
    @XmlRootElement
    @XmlAccessorType(XmlAccessType.PROPERTY)
    @XmlSeeAlso(AtlasClassificationDef.class)
    public static class AtlasClassificationDefs extends PList<AtlasClassificationDef> {
        private static final long serialVersionUID = 1L;

        public AtlasClassificationDefs() {
            super();
        }

        public AtlasClassificationDefs(List<AtlasClassificationDef> list) {
            super(list);
        }

        public AtlasClassificationDefs(List list, long startIndex, int pageSize, long totalCount,
                                       SortType sortType, String sortBy) {
            super(list, startIndex, pageSize, totalCount, sortType, sortBy);
        }
    }

}
