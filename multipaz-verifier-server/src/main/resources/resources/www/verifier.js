
// Keep in sync with verifier.html
var selectedProtocol = 'w3c_dc_mdoc_api'

// If the user clicks on one of the protocol entries, that becomes both the selected and the
// preferred protocol. If the selected protocol is disabled (because, for instance, the user selects
// a document that doesn't support the selected protocol), the selected protocol will be updated but
// the preferred one will remain the same. Then if the preferred one is enabled again, the selection
// will change back to the preferred protocol.
var preferredProtocol = selectedProtocol

var openid4vpUri = ''

async function onLoad() {
    const protocolDropdown = document.getElementById('protocolDropdown')
    protocolDropdown.addEventListener('hide.bs.dropdown', event => {
        var target = event.clickEvent.target
        var selected = target.getAttribute('value')
        if (selected === 'w3c_dc_mdoc_api' ||
            selected === 'w3c_dc_openid4vp_24' ||
            selected === 'w3c_dc_openid4vp_29' ||
            selected === 'w3c_dc_openid4vp_29_and_mdoc_api' ||
            selected === 'w3c_dc_openid4vp_24_and_mdoc_api' ||
            selected === 'w3c_dc_mdoc_api_and_openid4vp_29' ||
            selected === 'w3c_dc_mdoc_api_and_openid4vp_24' ||
            selected === 'uri_scheme_openid4vp_29' ||
            selected === 'uri_scheme_annex_a'
        ) {
            selectedProtocol = selected
            preferredProtocol = selectedProtocol
            protocolDropdown.innerHTML = target.innerHTML

            const openid4vp_sign_request_checkbox = document.getElementById("openid4vp-sign-request")
            openid4vp_sign_request_checkbox.hidden = (
                selected !== 'w3c_dc_openid4vp_24' &&
                selected !== 'w3c_dc_openid4vp_29' &&
                selected !== 'w3c_dc_openid4vp_29_and_mdoc_api' &&
                selected !== 'w3c_dc_openid4vp_24_and_mdoc_api' &&
                selected !== 'w3c_dc_mdoc_api_and_openid4vp_29' &&
                selected !== 'w3c_dc_mdoc_api_and_openid4vp_24'
            )

            const openid4vp_encrypt_response_checkbox = document.getElementById("openid4vp-encrypt-response")
            openid4vp_encrypt_response_checkbox.hidden = (
                selected !== 'w3c_dc_openid4vp_24' &&
                selected !== 'w3c_dc_openid4vp_29' &&
                selected !== 'w3c_dc_openid4vp_29_and_mdoc_api' &&
                selected !== 'w3c_dc_openid4vp_24_and_mdoc_api' &&
                selected !== 'w3c_dc_mdoc_api_and_openid4vp_29' &&
                selected !== 'w3c_dc_mdoc_api_and_openid4vp_24' &&
                selected !== 'uri_scheme_openid4vp_29'
            )

            const scheme = document.getElementById("scheme-form")
            scheme.hidden = (
                selected !== 'uri_scheme_openid4vp_29'
            )
        }
    })

    // Ask server what document types / requests are available and use this to
    // dynamically generate the UI..
    //
    const response = await callServer(
        'getAvailableRequests', {}
    )
    var active = true
    for (const dtwr of response.documentTypesWithRequests) {
      if (dtwr.mdocDocType != null) {
          var tabId = "mdoc-" + dtwr.mdocDocType
          addTab(dtwr.documentDisplayName + " (mdoc)", "mdoc", dtwr.mdocDocType, dtwr.sampleRequests, active, null)
          active = false
      }
      if (dtwr.vcVct != null) {
          var tabId = "vc-" + dtwr.vcVct
          addTab(dtwr.documentDisplayName + " (VC)", "vc", dtwr.vcVct, dtwr.sampleRequests, active, null)
          active = false
      }
    }
    for (const mdr of response.multiDocumentRequests) {
      console.log("mdr: id=" + mdr.id + " dn=" + mdr.displayName)
    }
    addTab("Multi-Document", "multiDocument", "any", null, false, response.multiDocumentRequests)
    addTab("Raw DCQL", "rawDcql", "any", null, false, null)
    rawDcqlReset_mdl1()
}

function addTab(tabName, mdocOrVc, docTypeOrVct, sampleRequests, active, multiDocumentRequests) {
    // For the tab ID to be queryable using jQuery, we need to mask out special characters. Replace
    // anything that isn't a letter or number.
    var escapedDocTypeOrVct = docTypeOrVct.replace(/[^a-zA-Z0-9]/g,'_');
    var tabId = mdocOrVc + '-' + escapedDocTypeOrVct
    var activeStr = active ? "active" : ""
    $('<li class="nav-item" role="presentation">' +
    '<button class="nav-link ' + activeStr + '" data-bs-toggle="pill" id="pills-tab-' + tabId + '" data-bs-target="#pills-' + tabId + '" type="button" role="tab" aria-controls="pills-home" aria-selected="true">' +
      tabName +
    '</button>' +
    '</li>')
    .appendTo('#pills-tab')

    var str = '<div class="tab-pane fade show ' + activeStr + '" '
    str += 'id="pills-' + tabId + '" role="tabpanel" '
    str += 'aria-labelledby="pills-tab-' + tabId + '" tabindex="0"> '
    if (multiDocumentRequests != null) {
        str += '  <div class="d-grid gap-2 mx-auto"> '
        for (mdr of multiDocumentRequests) {
            str += '    <button type="button" class="btn btn-primary btn-lg" '
            str += 'onclick="requestDocumentMulti(\'' + mdr.id + '\')" >'
            str += mdr.displayName
            str += '    </button> '
        }
        str += '  </div> '
    } else if (sampleRequests == null) {
        // Raw DCQL box
        str += '  <div class="d-grid gap-2 mx-auto"> '
        str += '    <textarea class="form-control" id="rawDclqTextArea" rows="12">'
        str += '</textarea>'
        str += '<div class="d-grid gap-2 mx-auto">'
        str += '<button type="button" class="btn btn-secondary btn-sm" onclick="rawDcqlReset_mdl1()">Reset (mDL, age_over_21 + portrait)</button> '
        str += '<button type="button" class="btn btn-secondary btn-sm" onclick="rawDcqlReset_photoid_zkp()">Reset (PhotoID, age_over_18, ZKP)</button> '
        str += '<button type="button" class="btn btn-secondary btn-sm" onclick="rawDcqlReset_sdjwt1()">Reset (SD-JWT VC EU PID, age_equals_or_over.18 + picture)</button> '
        str += '<button type="button" class="btn btn-secondary btn-sm" onclick="rawDcqlReset_age_mdocs()">Reset (#9: Age mDocs)</button> '
        str += '<button type="button" class="btn btn-secondary btn-sm" onclick="rawDcqlReset_mdl_or_pid_sdjwt()">Reset (#13: mDL mdoc OR PID sdjwt)</button> '
        str += '<button type="button" class="btn btn-secondary btn-sm" onclick="rawDcqlReset_mdl_or_pid()">Reset (mDL mdoc OR PID mdoc)</button> '
        str += '<button type="button" class="btn btn-secondary btn-sm" onclick="rawDcqlReset_mdl_and_pid()">Reset (mDL mdoc AND PID mdoc)</button> '
        str += '<button type="button" class="btn btn-secondary btn-sm" onclick="rawDcqlReset_mdl_or_photoid()">Reset (mDL mdoc OR PhotoID mdoc)</button> '
        str += '<button type="button" class="btn btn-secondary btn-sm" onclick="rawDcqlReset_mdl_and_photoid()">Reset (mDL mdoc AND PhotoID mdoc)</button> '
        str += '<button type="button" class="btn btn-secondary btn-sm" onclick="rawDcqlReset_complex_credential_set()">Reset (Complex credential_set OpenID4VP Appendix D)</button> '
        str += '<button type="button" class="btn btn-secondary btn-sm" onclick="rawDcqlReset_mdl_pid_photoid_mandatory()">Reset (mDL + PID + PhotoID)</button> '
        str += '<button type="button" class="btn btn-secondary btn-sm" onclick="rawDcqlReset_movie_and_id()">Reset (Movie Ticket + ID)</button> '
        str += '<button type="button" class="btn btn-secondary btn-sm" onclick="rawDcqlReset_movie_and_id_alt()">Reset (Movie Ticket + ID, Alt)</button> '
        str += '    <button type="button" class="btn btn-primary btn-sm" onclick="requestDocumentRawDcql()" >'
        str += 'Request'
        str += '    </button> '
        str += '</div>'
        str += '  </div> '
    } else {
        str += '  <div class="d-grid gap-2 mx-auto"> '
        for (sr of sampleRequests) {
            str += '    <button type="button" class="btn btn-primary btn-lg" '
            str += 'onclick="requestDocument(\'' + mdocOrVc + '\', \'' + docTypeOrVct + '\', \'' + sr.id + '\', null, null)" >'
            str += sr.displayName
            str += '    </button> '
        }
        str += '  </div> '
    }
    str += '</div> '

    $(str).appendTo('#pills-tabContent')

    // When one of the document tabs is selected, update the available protocol dropdown options.
    $('#pills-tab-' + tabId).on('shown.bs.tab', function (e) {
        updateProtocolOptions(mdocOrVc);
    });
}

function rawDcqlReset_mdl1() {
  const textArea = document.getElementById('rawDclqTextArea')
  textArea.value = `{
  "credentials": [
    {
      "id": "mdoc",
      "format": "mso_mdoc",
      "meta": {
        "doctype_value": "org.iso.18013.5.1.mDL"
      },
      "claims": [
        {
          "path": [
            "org.iso.18013.5.1",
            "age_over_21"
          ]
        },
        {
          "path": [
            "org.iso.18013.5.1",
            "portrait"
          ]
        }
      ]
    }
  ]
}
`;
}

function rawDcqlReset_photoid_zkp() {
  const textArea = document.getElementById('rawDclqTextArea')
  textArea.value = `{
  "credentials": [
    {
      "id": "mdoc",
      "format": "mso_mdoc_zk",
      "meta": {
        "doctype_value": "org.iso.23220.photoid.1",
        "zk_system_type": [
          {
            "system": "longfellow-libzk-v1",
            "id": "longfellow-libzk-v1_6_1_4096_2945_137e5a75ce72735a37c8a72da1a8a0a5df8d13365c2ae3d2c2bd6a0e7197c7c6",
            "version": 6,
            "circuit_hash": "137e5a75ce72735a37c8a72da1a8a0a5df8d13365c2ae3d2c2bd6a0e7197c7c6",
            "num_attributes": 1,
            "block_enc_hash": 4096,
            "block_enc_sig": 2945
          }
        ]
      },
      "claims": [
        {
          "path": [
            "org.iso.23220.1",
            "age_over_18"
          ]
        }
      ]
    }
  ]
}
`;
}

function rawDcqlReset_sdjwt1() {
  const textArea = document.getElementById('rawDclqTextArea')
  textArea.value = `{
  "credentials": [
    {
      "id": "pid",
      "format": "dc+sd-jwt",
      "meta": {
        "vct_values": [
          "urn:eudi:pid:1"
        ]
      },
      "claims": [
        {
          "path": [
            "age_equal_or_over",
            "18"
          ]
        },
        {
          "path": [
            "picture"
          ]
        }
      ]
    }
  ]
}
`;
}

function rawDcqlReset_age_mdocs() {
  const textArea = document.getElementById('rawDclqTextArea')
  textArea.value = `{
  "credentials": [
    {
      "id": "pid",
      "format": "mso_mdoc",
      "meta": {
        "doctype_value": "eu.europa.ec.eudi.pid.1"
      },
      "claims": [
        {
          "path": [
            "eu.europa.ec.eudi.pid.1",
            "age_over_18"
          ],
          "values": [
            true
          ]
        }
      ]
    },
    {
      "id": "mdl",
      "format": "mso_mdoc",
      "meta": {
        "doctype_value": "org.iso.18013.5.1.mDL"
      },
      "claims": [
        {
          "path": [
            "org.iso.18013.5.1",
            "age_over_18"
          ],
          "values": [
            true
          ]
        }
      ]
    },
    {
      "id": "photoid",
      "format": "mso_mdoc",
      "meta": {
        "doctype_value": "org.iso.23220.photoid.1"
      },
      "claims": [
        {
          "path": [
            "org.iso.23220.1",
            "age_over_18"
          ],
          "values": [
            true
          ]
        }
      ]
    }
  ],
  "credential_sets": [
    {
      "options": [
        [
          "pid"
        ],
        [
          "mdl"
        ],
        [
          "photoid"
        ]
      ]
    }
  ]
}
`;
}

function rawDcqlReset_mdl_or_pid_sdjwt() {
  const textArea = document.getElementById('rawDclqTextArea')
  textArea.value = `{
  "credentials": [
    {
      "id": "mdl",
      "format": "mso_mdoc",
      "meta": {
        "doctype_value": "org.iso.18013.5.1.mDL"
      },
      "claims": [
        {
          "path": [
            "org.iso.18013.5.1",
            "given_name"
          ]
        },
        {
          "path": [
            "org.iso.18013.5.1",
            "family_name"
          ]
        }
      ]
    },
    {
      "id": "pid",
      "format": "dc+sd-jwt",
      "meta": {
        "vct_values": [
          "urn:eudi:pid:1"
        ]
      },
      "claims": [
        {
          "path": [
            "family_name"
          ]
        },
        {
          "path": [
            "given_name"
          ]
        }
      ]
    }
  ],
  "credential_sets": [
    {
      "options": [
        [
          "mdl"
        ],
        [
          "pid"
        ]
      ]
    }
  ]
}
`;
}


function rawDcqlReset_mdl_or_pid() {
  const textArea = document.getElementById('rawDclqTextArea')
  textArea.value = `{
  "credentials": [
    {
      "id": "mdl",
      "format": "mso_mdoc",
      "meta": {
        "doctype_value": "org.iso.18013.5.1.mDL"
      },
      "claims": [
        {
          "path": [
            "org.iso.18013.5.1",
            "given_name"
          ]
        },
        {
          "path": [
            "org.iso.18013.5.1",
            "family_name"
          ]
        }
      ]
    },
    {
      "id": "pid",
      "format": "mso_mdoc",
      "meta": {
        "doctype_value": "eu.europa.ec.eudi.pid.1"
      },
      "claims": [
        {
          "path": [
            "eu.europa.ec.eudi.pid.1",
            "given_name"
          ]
        },
        {
          "path": [
            "eu.europa.ec.eudi.pid.1",
            "family_name"
          ]
        }
      ]
    }
  ],
  "credential_sets": [
    {
      "options": [
        [
          "mdl"
        ],
        [
          "pid"
        ]
      ]
    }
  ]
}
`;
}

function rawDcqlReset_mdl_and_pid() {
  const textArea = document.getElementById('rawDclqTextArea')
  textArea.value = `{
  "credentials": [
    {
      "id": "mdl",
      "format": "mso_mdoc",
      "meta": {
        "doctype_value": "org.iso.18013.5.1.mDL"
      },
      "claims": [
        {
          "path": [
            "org.iso.18013.5.1",
            "given_name"
          ]
        },
        {
          "path": [
            "org.iso.18013.5.1",
            "family_name"
          ]
        }
      ]
    },
    {
      "id": "pid",
      "format": "mso_mdoc",
      "meta": {
        "doctype_value": "eu.europa.ec.eudi.pid.1"
      },
      "claims": [
        {
          "path": [
            "eu.europa.ec.eudi.pid.1",
            "given_name"
          ]
        },
        {
          "path": [
            "eu.europa.ec.eudi.pid.1",
            "family_name"
          ]
        }
      ]
    }
  ],
  "credential_sets": [
    {
      "options": [
        [
          "mdl", "pid"
        ]
      ]
    }
  ]
}
`;
}

function rawDcqlReset_mdl_or_photoid() {
  const textArea = document.getElementById('rawDclqTextArea')
  textArea.value = `{
  "credentials": [
    {
      "id": "mdl",
      "format": "mso_mdoc",
      "meta": {
        "doctype_value": "org.iso.18013.5.1.mDL"
      },
      "claims": [
        {
          "path": [
            "org.iso.18013.5.1",
            "given_name"
          ]
        },
        {
          "path": [
            "org.iso.18013.5.1",
            "family_name"
          ]
        }
      ]
    },
    {
      "id": "photoid",
      "format": "mso_mdoc",
      "meta": {
        "doctype_value": "org.iso.23220.photoid.1"
      },
      "claims": [
        {
          "path": [
            "org.iso.23220.1",
            "given_name"
          ]
        },
        {
          "path": [
            "org.iso.23220.1",
            "family_name"
          ]
        }
      ]
    }
  ],
  "credential_sets": [
    {
      "options": [
        [
          "mdl"
        ],
        [
          "photoid"
        ]
      ]
    }
  ]
}
`;
}

function rawDcqlReset_mdl_and_photoid() {
  const textArea = document.getElementById('rawDclqTextArea')
  textArea.value = `{
  "credentials": [
    {
      "id": "mdl",
      "format": "mso_mdoc",
      "meta": {
        "doctype_value": "org.iso.18013.5.1.mDL"
      },
      "claims": [
        {
          "path": [
            "org.iso.18013.5.1",
            "given_name"
          ]
        },
        {
          "path": [
            "org.iso.18013.5.1",
            "family_name"
          ]
        }
      ]
    },
    {
      "id": "photoid",
      "format": "mso_mdoc",
      "meta": {
        "doctype_value": "org.iso.23220.photoid.1"
      },
      "claims": [
        {
          "path": [
            "org.iso.23220.1",
            "given_name"
          ]
        },
        {
          "path": [
            "org.iso.23220.1",
            "family_name"
          ]
        }
      ]
    }
  ],
  "credential_sets": [
    {
      "options": [
        [
          "mdl", "photoid"
        ]
      ]
    }
  ]
}
`;
}

function rawDcqlReset_complex_credential_set() {
  const textArea = document.getElementById('rawDclqTextArea')
  textArea.value = `{
  "credentials": [
    {
      "id": "pid",
      "format": "dc+sd-jwt",
      "meta": {
        "vct_values": ["https://credentials.example.com/identity_credential"]
      },
      "claims": [
        {"path": ["given_name"]},
        {"path": ["family_name"]},
        {"path": ["address", "street_address"]}
      ]
    },
    {
      "id": "other_pid",
      "format": "dc+sd-jwt",
      "meta": {
        "vct_values": ["https://othercredentials.example/pid"]
      },
      "claims": [
        {"path": ["given_name"]},
        {"path": ["family_name"]},
        {"path": ["address", "street_address"]}
      ]
    },
    {
      "id": "pid_reduced_cred_1",
      "format": "dc+sd-jwt",
      "meta": {
        "vct_values": ["https://credentials.example.com/reduced_identity_credential"]
      },
      "claims": [
        {"path": ["family_name"]},
        {"path": ["given_name"]}
      ]
    },
    {
      "id": "pid_reduced_cred_2",
      "format": "dc+sd-jwt",
      "meta": {
        "vct_values": ["https://cred.example/residence_credential"]
      },
      "claims": [
        {"path": ["postal_code"]},
        {"path": ["locality"]},
        {"path": ["region"]}
      ]
    },
    {
      "id": "nice_to_have",
      "format": "dc+sd-jwt",
      "meta": {
        "vct_values": ["https://company.example/company_rewards"]
      },
      "claims": [
        {"path": ["rewards_number"]}
      ]
    }
  ],
  "credential_sets": [
    {
      "options": [
        [ "pid" ],
        [ "other_pid" ],
        [ "pid_reduced_cred_1", "pid_reduced_cred_2" ]
      ]
    },
    {
      "required": false,
      "options": [
        [ "nice_to_have" ]
      ]
    }
  ]
}
`;
}

function rawDcqlReset_mdl_pid_photoid_mandatory() {
  const textArea = document.getElementById('rawDclqTextArea')
  textArea.value = `{
  "credentials": [
    {
      "id": "mdl",
      "format": "mso_mdoc",
      "meta": {
        "doctype_value": "org.iso.18013.5.1.mDL"
      },
      "claims": [
        { "path": ["org.iso.18013.5.1", "family_name" ] },
        { "path": ["org.iso.18013.5.1", "given_name" ] },
        { "path": ["org.iso.18013.5.1", "birth_date" ] },
        { "path": ["org.iso.18013.5.1", "issue_date" ] },
        { "path": ["org.iso.18013.5.1", "expiry_date" ] },
        { "path": ["org.iso.18013.5.1", "issuing_country" ] },
        { "path": ["org.iso.18013.5.1", "issuing_authority" ] },
        { "path": ["org.iso.18013.5.1", "document_number" ] },
        { "path": ["org.iso.18013.5.1", "portrait" ] },
        { "path": ["org.iso.18013.5.1", "driving_privileges" ] },
        { "path": ["org.iso.18013.5.1", "un_distinguishing_sign" ] }
      ]
    },
    {
      "id": "pid",
      "format": "mso_mdoc",
      "meta": {
        "doctype_value": "eu.europa.ec.eudi.pid.1"
      },
      "claims": [
        { "path": ["eu.europa.ec.eudi.pid.1", "family_name" ] },
        { "path": ["eu.europa.ec.eudi.pid.1", "given_name" ] },
        { "path": ["eu.europa.ec.eudi.pid.1", "birth_date" ] },
        { "path": ["eu.europa.ec.eudi.pid.1", "birth_place" ] },
        { "path": ["eu.europa.ec.eudi.pid.1", "nationality" ] },
        { "path": ["eu.europa.ec.eudi.pid.1", "expiry_date" ] },
        { "path": ["eu.europa.ec.eudi.pid.1", "issuing_authority" ] },
        { "path": ["eu.europa.ec.eudi.pid.1", "issuing_country" ] }
      ]
    },
    {
      "id": "photoid",
      "format": "mso_mdoc",
      "meta": {
        "doctype_value": "org.iso.23220.photoid.1"
      },
      "claims": [
        { "path": ["org.iso.23220.1", "family_name" ] },
        { "path": ["org.iso.23220.1", "given_name" ] },
        { "path": ["org.iso.23220.1", "birth_date" ] },
        { "path": ["org.iso.23220.1", "portrait" ] },
        { "path": ["org.iso.23220.1", "issue_date" ] },
        { "path": ["org.iso.23220.1", "expiry_date" ] },
        { "path": ["org.iso.23220.1", "issuing_authority_unicode" ] },
        { "path": ["org.iso.23220.1", "issuing_country" ] },
        { "path": ["org.iso.23220.1", "age_over_18" ] }
      ]
    }
  ]
}
`;
}

function rawDcqlReset_movie_and_id() {
  const textArea = document.getElementById('rawDclqTextArea')
  textArea.value = `{
  "credential_sets": [
    {
      "options": [
        [ "mdl" ],
        [ "pid" ],
        [ "photoid" ]
      ]
    },
    {
      "options": [
        [ "movieticket" ]
      ]
    }
  ],
  "credentials": [
    {
      "id": "mdl",
      "format": "mso_mdoc",
      "meta": {
        "doctype_value": "org.iso.18013.5.1.mDL"
      },
      "claims": [
        { "path": ["org.iso.18013.5.1", "family_name" ] },
        { "path": ["org.iso.18013.5.1", "given_name" ] },
        { "path": ["org.iso.18013.5.1", "portrait" ] }
      ]
    },
    {
      "id": "pid",
      "format": "mso_mdoc",
      "meta": {
        "doctype_value": "eu.europa.ec.eudi.pid.1"
      },
      "claims": [
        { "path": ["eu.europa.ec.eudi.pid.1", "family_name" ] },
        { "path": ["eu.europa.ec.eudi.pid.1", "given_name" ] },
        { "path": ["eu.europa.ec.eudi.pid.1", "portrait" ] }
      ]
    },
    {
      "id": "photoid",
      "format": "mso_mdoc",
      "meta": {
        "doctype_value": "org.iso.23220.photoid.1"
      },
      "claims": [
        { "path": ["org.iso.23220.1", "family_name" ] },
        { "path": ["org.iso.23220.1", "given_name" ] },
        { "path": ["org.iso.23220.1", "portrait" ] }
      ]
    },
    {
      "id": "movieticket",
      "format": "dc+sd-jwt",
      "meta": {
        "vct_values": ["https://utopia.example.com/vct/movieticket"]
      },
      "claims": [
        {"path": ["ticket_number"]},
        {"path": ["cinema_id"]}
      ]
    }
  ]
}
`;
}

function rawDcqlReset_movie_and_id_alt() {
  const textArea = document.getElementById('rawDclqTextArea')
  textArea.value = `{
  "credential_sets": [
    {
      "options": [
        [ "mdl", "movieticket" ],
        [ "pid", "movieticket" ],
        [ "photoid", "movieticket" ]
      ]
    }
  ],
  "credentials": [
    {
      "id": "mdl",
      "format": "mso_mdoc",
      "meta": {
        "doctype_value": "org.iso.18013.5.1.mDL"
      },
      "claims": [
        { "path": ["org.iso.18013.5.1", "family_name" ] },
        { "path": ["org.iso.18013.5.1", "given_name" ] },
        { "path": ["org.iso.18013.5.1", "portrait" ] }
      ]
    },
    {
      "id": "pid",
      "format": "mso_mdoc",
      "meta": {
        "doctype_value": "eu.europa.ec.eudi.pid.1"
      },
      "claims": [
        { "path": ["eu.europa.ec.eudi.pid.1", "family_name" ] },
        { "path": ["eu.europa.ec.eudi.pid.1", "given_name" ] },
        { "path": ["eu.europa.ec.eudi.pid.1", "portrait" ] }
      ]
    },
    {
      "id": "photoid",
      "format": "mso_mdoc",
      "meta": {
        "doctype_value": "org.iso.23220.photoid.1"
      },
      "claims": [
        { "path": ["org.iso.23220.1", "family_name" ] },
        { "path": ["org.iso.23220.1", "given_name" ] },
        { "path": ["org.iso.23220.1", "portrait" ] }
      ]
    },
    {
      "id": "movieticket",
      "format": "dc+sd-jwt",
      "meta": {
        "vct_values": ["https://utopia.example.com/vct/movieticket"]
      },
      "claims": [
        {"path": ["ticket_number"]},
        {"path": ["cinema_id"]}
      ]
    }
  ]
}
`;
}

function updateProtocolOptions(mdocOrVc) {
    const protocolDropdown = document.getElementById('protocolDropdown')
    const mdocOnly = document.querySelectorAll('.mdoc-only');

    if (mdocOrVc === 'mdoc' || mdocOrVc === 'rawDcql' || mdocOrVc == 'multiDocument') {
        // Enable mdoc-only options for mdoc entries
        mdocOnly.forEach(option => {
            option.classList.remove('disabled');
            option.removeAttribute('disabled');
            // If the preferred protocol was just reenabled, set it as the selected protocol.
            if (preferredProtocol == option.getAttribute('value')) {
                selectedProtocol = preferredProtocol
                protocolDropdown.innerHTML = option.innerHTML;
            }
        });
    } else {
        // Disable mdoc-only options for non-mdoc entries
        mdocOnly.forEach(option => {
            option.classList.add('disabled');
            option.setAttribute('disabled', 'disabled');
            if (selectedProtocol == option.getAttribute('value')) {
                selectedProtocol = null
            }
        });
        // If the selected protocol was disabled, select the next non-disabled protocol.
        if (selectedProtocol == null) {
            const firstEnabledOption = document.querySelector('.dropdown-item:not(.disabled)');
            selectedProtocol = firstEnabledOption.getAttribute('value');
            protocolDropdown.innerHTML = firstEnabledOption.innerHTML;
        }
    }

    const openid4vp_sign_request_checkbox = document.getElementById("openid4vp-sign-request")
    openid4vp_sign_request_checkbox.hidden = (
        selectedProtocol !== 'w3c_dc_openid4vp_24' &&
        selectedProtocol !== 'w3c_dc_openid4vp_29' &&
        selectedProtocol !== 'w3c_dc_openid4vp_29_and_mdoc_api' &&
        selectedProtocol !== 'w3c_dc_openid4vp_24_and_mdoc_api' &&
        selectedProtocol !== 'w3c_dc_mdoc_api_and_openid4vp_29' &&
        selectedProtocol !== 'w3c_dc_mdoc_api_and_openid4vp_24'
    )
    const openid4vp_encrypt_response_checkbox = document.getElementById("openid4vp-encrypt-response")
    openid4vp_encrypt_response_checkbox.hidden = (
        selectedProtocol !== 'w3c_dc_openid4vp_24' &&
        selectedProtocol !== 'w3c_dc_openid4vp_29' &&
        selectedProtocol !== 'w3c_dc_openid4vp_29_and_mdoc_api' &&
        selectedProtocol !== 'w3c_dc_openid4vp_24_and_mdoc_api' &&
        selectedProtocol !== 'w3c_dc_mdoc_api_and_openid4vp_29' &&
        selectedProtocol !== 'w3c_dc_mdoc_api_and_openid4vp_24' &&
        selectedProtocol !== 'uri_scheme_openid4vp_29'
    )
}

async function onLoadRedirect() {
    const urlParams = new URLSearchParams(location.search);
    const sessionId = urlParams.get('sessionId');
    const response = await callServer(
        'openid4vpGetData',
        {
            sessionId: sessionId,
        }
    )
    var tbodyRef = document.getElementById('resultTable').getElementsByTagName('tbody')[0]
    for (const page of response.pages) {
        for (const line of page.lines) {
            var newRow = tbodyRef.insertRow()
            var keyCell = newRow.insertCell()
            keyCell.appendChild(document.createTextNode(line.key))
            var valueCell = newRow.insertCell()
            valueCell.appendChild(document.createTextNode(line.value))
        }
    }
    console.log(response)
}

function redirectClose() {
    console.log('redirectClose')
    window.close()
}

async function requestDocumentMulti(multiDocumentRequestId) {
    requestDocument("", "", "", null, multiDocumentRequestId)
}

async function requestDocumentRawDcql() {
    const textArea = document.getElementById('rawDclqTextArea')
    const rawDcql = textArea.value
    requestDocument("", "", "", rawDcql, null)
}

async function requestDocument(format, docType, requestId, rawDcql, multiDocumentRequestId) {
    console.log('requestDocument, format=' + format + ' docType=' + docType + ' requestId=' + requestId + ' protocol=' + selectedProtocol)
    if (selectedProtocol === 'uri_scheme_openid4vp_29') {
        if (document.getElementById("scheme-input").value === "") {
            alert("You must specify a non-empty scheme")
            return
        }
        var signRequest = document.getElementById("openid4vp-sign-request-input").checked
        var encryptResponse = document.getElementById("openid4vp-encrypt-response-input").checked
        const response = await callServer(
            'openid4vpBegin',
            {
                format: format,
                docType: docType,
                requestId: requestId,
                rawDcql: rawDcql != null ? rawDcql : "",
                multiDocumentRequestId: multiDocumentRequestId != null ? multiDocumentRequestId : "",
                protocol: selectedProtocol,
                origin: location.origin,
                host: location.host,
                scheme: document.getElementById("scheme-input").value,
                signRequest: true, // OpenID4VP 1.0 w/ URI scheme requires signed request
                encryptResponse: encryptResponse
            }
        )
        window.location = response.uri
    } else if (selectedProtocol === 'uri_scheme_annex_a') {
        const response = await callServer(
            'annexABegin',
            {
                format: format,
                docType: docType,
                requestId: requestId,
                rawDcql: rawDcql != null ? rawDcql : "",
                multiDocumentRequestId: multiDocumentRequestId != null ? multiDocumentRequestId : "",
                protocol: selectedProtocol,
                origin: location.origin,
                host: location.host,
            }
        )
        window.location = response.uri
        const credentialResponse = await callServer(
            'annexAGetData',
            {
                sessionId: response.sessionId,
            }
        )
        showResponse(credentialResponse)
    } else if (selectedProtocol === "w3c_dc_mdoc_api" ||
               selectedProtocol === "w3c_dc_openid4vp_24" ||
               selectedProtocol === 'w3c_dc_openid4vp_29' ||
               selectedProtocol === 'w3c_dc_openid4vp_29_and_mdoc_api' ||
               selectedProtocol === 'w3c_dc_openid4vp_24_and_mdoc_api' ||
               selectedProtocol === 'w3c_dc_mdoc_api_and_openid4vp_29' ||
               selectedProtocol === 'w3c_dc_mdoc_api_and_openid4vp_24') {
        try {
            var signRequest = document.getElementById("openid4vp-sign-request-input").checked
            var encryptResponse = document.getElementById("openid4vp-encrypt-response-input").checked
            const response = await callServer(
                'dcBegin',
                {
                    format: format,
                    docType: docType,
                    requestId: requestId,
                    rawDcql: rawDcql != null ? rawDcql : "",
                    multiDocumentRequestId: multiDocumentRequestId != null ? multiDocumentRequestId : "",
                    protocol: selectedProtocol,
                    origin: location.origin,
                    host: location.host,
                    signRequest: signRequest,
                    encryptResponse: encryptResponse
                }
            )
            console.log(response)
            if (response.error != null) {
                alert("Something went wrong: " + response.error)
            } else {
                var requestString = JSON.parse(response.dcRequestString)
                var requestString2 = null
                if (response.dcRequestString2 != null) {
                    requestString2 = JSON.parse(response.dcRequestString2)
                }
                dcRequestCredential(
                    response.sessionId,
                    response.dcRequestProtocol,
                    requestString,
                    response.dcRequestProtocol2,
                    requestString2
                )
            }
        } catch (err) {
            alert("Something went wrong: " + err)
        }
    }
}

async function dcRequestCredential(sessionId, dcRequestProtocol, dcRequest, dcRequestProtocol2, dcRequest2) {
    if (!navigator.credentials || !navigator.credentials.get) {
        alert("Digital Credentials API is not available. Please enable it via chrome://flags#web-identity-digital-credentials.");
        return;
    }
    try {
        console.log('protocol: ', dcRequestProtocol)
        console.log('request: ', dcRequest)
        var requests = []
        requests.push({
            protocol: dcRequestProtocol,
            data: dcRequest
        })
        if (dcRequestProtocol2 != null) {
            console.log('protocol2: ', dcRequestProtocol2)
            console.log('request2: ', dcRequest2)
            requests.push({
                protocol: dcRequestProtocol2,
                data: dcRequest2
            })
        }
        const credentialResponse = await navigator.credentials.get({
            digital: {
                requests: requests
            },
            mediation: 'required',
          })
        console.log('credentialResponse ', credentialResponse)
        dcProcessResponse(sessionId, credentialResponse)
    } catch (err) {
        alert(err)
    }
}

async function dcProcessResponse(sessionId, credentialResponse) {
    var dataStr
    if (typeof(credentialResponse.data) == 'string') {
        dataStr = credentialResponse.data
    } else {
	    dataStr = JSON.stringify(credentialResponse.data)
    }
    const response = await callServer(
        'dcGetData',
        {
            sessionId: sessionId,
            credentialProtocol: credentialResponse.protocol,
            credentialResponse: dataStr
        }
    )
    showResponse(response)
}

async function showResponse(credentialResponse) {
    var modalTitle = document.getElementById('dcResultModalLabel')
    modalTitle.innerHTML = 'Received ' + credentialResponse.pages.length + ' credentials'
    var modalBody = document.getElementById('dcResultModal').querySelector('.list-group')
    modalBody.innerHTML = ''
    var pageNum = 0
    for (const page of credentialResponse.pages) {
        if (pageNum++ != 0) {
          modalBody.innerHTML += '<li class="list-group-item d-flex justify-content-between align-items-start"><div class="ms-2 me-auto"><div class="fw-bold">===========</div></div></li>'
        }
        for (const line of page.lines) {
            modalBody.innerHTML += '<li class="list-group-item d-flex justify-content-between align-items-start"><div class="ms-2 me-auto"><div class="fw-bold">' + line.key + '</div>' + line.value + '</div></li>'
        }
    }
    var modal = new bootstrap.Modal(document.getElementById('dcResultModal'), {})
    modal.show()
}

function openid4vpAuthenticateWithWallet() {
    console.log("Opening " + openid4vpUri)
    window.open(openid4vpUri)
}

async function callServer(command, params) {
    const response = await fetch(
        'verifier/' + command,
        {
            method: 'POST',
            headers: {
                'Accept': 'application/json',
                'Content-Type': 'application/json',
            },
            body: JSON.stringify(params)
        }
    )
    return await response.json()
}